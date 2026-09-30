package school.greenwood.community

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.client.request.forms.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.testing.*
import kotlin.test.*

private fun jetonDe(s: String): String =
    Regex(""""jeton":"([^"]+)"""").find(s)!!.groupValues[1]

private fun idDe(s: String): Long =
    Regex(""""id":(\d+)""").find(s)!!.groupValues[1].toLong()

private suspend fun io.ktor.client.HttpClient.nouveauDevoir(jeton: String): Long {
    val rep = post("/devoirs") {
        header("Authorization", "Bearer $jeton")
        contentType(ContentType.Application.Json)
        setBody("""{"matière":"SVT","contenu":"Lire le chapitre 3"}""")
    }
    assertEquals(HttpStatusCode.Created, rep.status)
    return idDe(rep.bodyAsText())
}

private suspend fun io.ktor.client.HttpClient.voter(id: Long, jeton: String, vote: Int) =
    post("/devoirs/$id/vote") {
        header("Authorization", "Bearer $jeton")
        contentType(ContentType.Application.Json)
        setBody("""{"vote":$vote}""")
    }

private suspend fun io.ktor.client.HttpClient.supprimer(chemin: String, jeton: String?) =
    delete(chemin) { jeton?.let { header("Authorization", "Bearer $it") } }

private suspend fun io.ktor.client.HttpClient.signaler(
    cible: String, cibleId: Long, jeton: String?, raison: String = "contenu inapproprié",
) = post("/signalements") {
    jeton?.let { header("Authorization", "Bearer $it") }
    contentType(ContentType.Application.Json)
    setBody("""{"cible":"$cible","cibleId":$cibleId,"raison":"$raison"}""")
}

private fun votesDe(corps: String): Int =
    Regex(""""votes":(-?\d+)""").find(corps)!!.groupValues[1].toInt()

private const val ORIGINE = "https://parent.greenwood.example"

/** Pré-vol tel que le navigateur l'envoie avant un appel cross-origin. */
private suspend fun io.ktor.client.HttpClient.prévol(origine: String, méthode: String = "POST") =
    options("/devoirs") {
        header(HttpHeaders.Origin, origine)
        header(HttpHeaders.AccessControlRequestMethod, méthode)
        header(HttpHeaders.AccessControlRequestHeaders, "Authorization, Content-Type")
    }

class ModuleTest {
    private fun Application.avecStockageTemporaire(
        jetonAdmin: String? = null,
        origines: Collection<String> = emptyList(),
    ) = module(stockageTemporaire(), jetonAdmin, origines = origines)

    @Test
    fun `les pièces jointes sont téléchargeables et supprimées avec le devoir`() = testApplication {
        val stockage = stockageTemporaire()
        application { module(stockage) }
        val jeton = jetonDe(client.post("/compte").bodyAsText())
        val id = client.nouveauDevoir(jeton)
        val contenu = "document de test".encodeToByteArray()
        val upload = client.post("/devoirs/$id/pieces-jointes") {
            header("Authorization", "Bearer $jeton")
            setBody(MultiPartFormDataContent(formData {
                append("file", contenu, Headers.build {
                    append(HttpHeaders.ContentDisposition, "filename=\"devoir.txt\"")
                    append(HttpHeaders.ContentType, "text/plain")
                })
            }))
        }
        assertEquals(HttpStatusCode.Created, upload.status)
        val meta = upload.bodyAsText()
        assertTrue(meta.contains("devoir.txt"))
        assertTrue(client.get("/devoirs").bodyAsText().contains("pieces-jointes"))
        val url = Regex(""""url":"([^"]+)"""").find(meta)!!.groupValues[1]
        val storedFile = stockage.fichierPièceJointe(Regex(""""id":"([^"]+)"""").find(meta)!!.groupValues[1])!!
        val téléchargement = client.get(url)
        assertEquals(HttpStatusCode.OK, téléchargement.status)
        assertContentEquals(contenu, téléchargement.readRawBytes())
        assertEquals(HttpStatusCode.NoContent, client.supprimer("/devoirs/$id", jeton).status)
        assertFalse(storedFile.exists(), "le fichier est supprimé du stockage")
        assertEquals(HttpStatusCode.NotFound, client.get(url).status)
    }

    @Test
    fun `une pièce jointe de plus de 5 Mo est refusée`() = testApplication {
        val stockage = stockageTemporaire()
        application { module(stockage) }
        val jeton = jetonDe(client.post("/compte").bodyAsText())
        val id = client.nouveauDevoir(jeton)
        val upload = client.post("/devoirs/$id/pieces-jointes") {
            header("Authorization", "Bearer $jeton")
            setBody(MultiPartFormDataContent(formData {
                append("file", ByteArray(5 * 1024 * 1024 + 1), Headers.build {
                    append(HttpHeaders.ContentDisposition, "filename=\"large.bin\"")
                    append(HttpHeaders.ContentType, "application/octet-stream")
                })
            }))
        }
        assertEquals(HttpStatusCode.PayloadTooLarge, upload.status)
        assertFalse(client.get("/devoirs").bodyAsText().contains("large.bin"))
    }

    @Test
    fun `health répond OK`() = testApplication {
        application { avecStockageTemporaire() }
        assertEquals("OK", client.get("/health").bodyAsText())
    }

    @Test
    fun `inscription renvoie un jeton de six mots`() = testApplication {
        application { avecStockageTemporaire() }
        val rep = client.post("/compte")
        assertEquals(HttpStatusCode.Created, rep.status)
        val jeton = jetonDe(rep.bodyAsText())
        assertEquals(6, jeton.split('-').size, "jeton = $jeton")
    }

    @Test
    fun `générerJeton utilise six mots distincts`() {
        repeat(50) {
            val mots = générerJeton().split('-')
            assertEquals(6, mots.size)
            assertEquals(mots.distinct().size, mots.size)
        }
    }

    @Test
    fun `la liste de mots contient les exemples de référence`() {
        val présents = listOf("eagle", "smell", "bootlace", "hypnoses", "saddlebag", "bunkhouse")
            .filterNot { it in MOTS }
        assertTrue(présents.isEmpty(), "mots manquants : $présents")
    }

    @Test
    fun `on peut poster un devoir avec un jeton`() = testApplication {
        application { avecStockageTemporaire() }
        val jeton = jetonDe(client.post("/compte").bodyAsText())
        val rep = client.post("/devoirs") {
            header("Authorization", "Bearer $jeton")
            contentType(ContentType.Application.Json)
            setBody("""{"matière":"Mathématiques","contenu":"Exercices 1 à 5 page 12"}""")
        }
        assertEquals(HttpStatusCode.Created, rep.status)
        val corps = client.get("/devoirs").bodyAsText()
        assertTrue(corps.contains("Exercices 1 à 5"))
        assertFalse(corps.contains(jeton), "le jeton de l'auteur reste secret")
    }

    @Test
    fun `poster un devoir sans jeton est refusé`() = testApplication {
        application { avecStockageTemporaire() }
        val rep = client.post("/devoirs") {
            contentType(ContentType.Application.Json)
            setBody("""{"matière":"Maths","contenu":"x"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, rep.status)
    }

    @Test
    fun `devoir incomplet est rejeté`() = testApplication {
        application { avecStockageTemporaire() }
        val jeton = jetonDe(client.post("/compte").bodyAsText())
        val rep = client.post("/devoirs") {
            header("Authorization", "Bearer $jeton")
            contentType(ContentType.Application.Json)
            setBody("""{"matière":"","contenu":""}""")
        }
        assertEquals(HttpStatusCode.BadRequest, rep.status)
    }

    @Test
    fun `on peut voter sur le devoir d'un autre`() = testApplication {
        application { avecStockageTemporaire() }
        val jetonA = jetonDe(client.post("/compte").bodyAsText())
        val jetonB = jetonDe(client.post("/compte").bodyAsText())
        val devoir = client.post("/devoirs") {
            header("Authorization", "Bearer $jetonA")
            contentType(ContentType.Application.Json)
            setBody("""{"matière":"SVT","contenu":"Lire le chapitre 3"}""")
        }.bodyAsText()
        val id = Regex(""""id":(\d+)""").find(devoir)!!.groupValues[1]
        val rep = client.post("/devoirs/$id/vote") {
            header("Authorization", "Bearer $jetonB")
            contentType(ContentType.Application.Json)
            setBody("""{"vote":1}""")
        }
        assertEquals(HttpStatusCode.OK, rep.status)
        assertTrue(rep.bodyAsText().contains(""""votes":1"""))
    }

    @Test
    fun `on ne vote pas pour sa propre suggestion`() = testApplication {
        application { avecStockageTemporaire() }
        val jeton = jetonDe(client.post("/compte").bodyAsText())
        val devoir = client.post("/devoirs") {
            header("Authorization", "Bearer $jeton")
            contentType(ContentType.Application.Json)
            setBody("""{"matière":"SVT","contenu":"Chapitre 3"}""")
        }.bodyAsText()
        val id = Regex(""""id":(\d+)""").find(devoir)!!.groupValues[1]
        val rep = client.post("/devoirs/$id/vote") {
            header("Authorization", "Bearer $jeton")
            contentType(ContentType.Application.Json)
            setBody("""{"vote":1}""")
        }
        assertEquals(HttpStatusCode.Forbidden, rep.status)
    }

    @Test
    fun `vote invalide est rejeté`() = testApplication {
        application { avecStockageTemporaire() }
        val jeton = jetonDe(client.post("/compte").bodyAsText())
        val rep = client.post("/devoirs/1/vote") {
            header("Authorization", "Bearer $jeton")
            contentType(ContentType.Application.Json)
            setBody("""{"vote":5}""")
        }
        assertEquals(HttpStatusCode.BadRequest, rep.status)
    }

    @Test
    fun `un second vote du même jeton remplace le premier`() = testApplication {
        application { avecStockageTemporaire() }
        val jetonA = jetonDe(client.post("/compte").bodyAsText())
        val jetonB = jetonDe(client.post("/compte").bodyAsText())
        val id = client.nouveauDevoir(jetonA)

        assertEquals(1, votesDe(client.voter(id, jetonB, 1).bodyAsText()))
        assertEquals(1, votesDe(client.voter(id, jetonB, 1).bodyAsText()), "revoter ne cumule pas")
        assertEquals(-1, votesDe(client.voter(id, jetonB, -1).bodyAsText()), "le vote remplace le précédent")
        assertEquals(-1, votesDe(client.voter(id, jetonB, -1).bodyAsText()))
        assertEquals(1, votesDe(client.voter(id, jetonB, 1).bodyAsText()))
    }

    @Test
    fun `les votes de jetons différents se cumulent`() = testApplication {
        application { avecStockageTemporaire() }
        val jetonA = jetonDe(client.post("/compte").bodyAsText())
        val jetonB = jetonDe(client.post("/compte").bodyAsText())
        val jetonC = jetonDe(client.post("/compte").bodyAsText())
        val id = client.nouveauDevoir(jetonA)

        assertEquals(HttpStatusCode.OK, client.voter(id, jetonB, 1).status)
        assertEquals(HttpStatusCode.OK, client.voter(id, jetonC, 1).status)
        val troisième = client.voter(id, jetonC, -1)
        assertEquals(""""votes":0""", Regex(""""votes":-?\d+""").find(troisième.bodyAsText())!!.value)
        assertTrue(client.get("/devoirs").bodyAsText().contains(""""votes":0"""))
    }

    @Test
    fun `le jeton de l'auteur ne figure dans aucune réponse publique`() = testApplication {
        application { avecStockageTemporaire() }
        val jetonA = jetonDe(client.post("/compte").bodyAsText())
        val jetonB = jetonDe(client.post("/compte").bodyAsText())

        val création = client.post("/devoirs") {
            header("Authorization", "Bearer $jetonA")
            contentType(ContentType.Application.Json)
            setBody("""{"matière":"SVT","contenu":"Chapitre 3"}""")
        }
        val id = idDe(création.bodyAsText())
        assertFalse(création.bodyAsText().contains(jetonA), "POST /devoirs ne renvoie pas l'auteur")
        assertFalse(client.get("/devoirs").bodyAsText().contains(jetonA), "GET /devoirs ne révèle pas l'auteur")
        assertFalse(client.voter(id, jetonB, 1).bodyAsText().contains(jetonA), "le vote ne révèle pas l'auteur")
    }

    @Test
    fun `les votes survivent à un rechargement`() {
        val s1 = stockageTemporaire()
        val id = s1.id()
        s1.ajouterDevoir(Devoir(id, "auteur-jeton", "Maths", "x", null, 0, 0))
        s1.voter(id, "autre-jeton", 1)
        s1.voter(id, "autre-jeton", -1)

        val s2 = rouvrir(s1)
        s2.charger()
        val votesRechargés: Map<String, Int> = s2.votes(id)
        assertEquals(mapOf("autre-jeton" to -1), votesRechargés)
        assertEquals(-1, s2.devoir(id)!!.votes)
        assertEquals(-1, s2.voter(id, "autre-jeton", -1)!!.votes,
            "le vote remplace toujours le précédent")
    }

    @Test
    fun `on peut signaler un problème d'emploi du temps`() = testApplication {
        application { avecStockageTemporaire() }
        val jeton = jetonDe(client.post("/compte").bodyAsText())
        val rep = client.post("/edt/problemes") {
            header("Authorization", "Bearer $jeton")
            contentType(ContentType.Application.Json)
            setBody("""{"description":"Cours de physique absent du lundi","date":"2026-09-28"}""")
        }
        assertEquals(HttpStatusCode.Created, rep.status)
        assertTrue(client.get("/edt/problemes").bodyAsText().contains("physique"))
    }

    @Test
    fun `on peut proposer une correction liée à un signalement`() = testApplication {
        application { avecStockageTemporaire() }
        val jeton = jetonDe(client.post("/compte").bodyAsText())
        val problème = client.post("/edt/problemes") {
            header("Authorization", "Bearer $jeton")
            contentType(ContentType.Application.Json)
            setBody("""{"description":"Salle erronée","date":"2026-09-28"}""")
        }.bodyAsText()
        val id = Regex(""""id":(\d+)""").find(problème)!!.groupValues[1]
        val rep = client.post("/edt/corrections") {
            header("Authorization", "Bearer $jeton")
            contentType(ContentType.Application.Json)
            setBody("""{"problèmeId":$id,"description":"Lundi 28 : physique en salle 204","date":"2026-09-28"}""")
        }
        assertEquals(HttpStatusCode.Created, rep.status)
        assertTrue(client.get("/edt/corrections").bodyAsText().contains("salle 204"))
    }

    @Test
    fun `correction avec problèmeId inconnu est rejetée`() = testApplication {
        application { avecStockageTemporaire() }
        val jeton = jetonDe(client.post("/compte").bodyAsText())
        val rep = client.post("/edt/corrections") {
            header("Authorization", "Bearer $jeton")
            contentType(ContentType.Application.Json)
            setBody("""{"problèmeId":999,"description":"x","date":"2026-09-28"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, rep.status)
    }

    @Test
    fun `le stockage survit à un rechargement`() {
        val s1 = stockageTemporaire()
        s1.créerCompte("eagle-smell-bootlace-hypnoses-saddlebag-bunkhouse")
        s1.ajouterDevoir(Devoir(s1.id(), "a", "Maths", "x", null, 0, 0))

        val s2 = rouvrir(s1)
        s2.charger()
        assertEquals(1, s2.devoirs(matière = null, depuis = null, "votes", 0, 50).éléments.size)
        assertTrue(s2.compteExiste("eagle-smell-bootlace-hypnoses-saddlebag-bunkhouse"))
    }

    // — Suppression ---------------------------------------------------------

    @Test
    fun `l'auteur peut supprimer son devoir`() = testApplication {
        application { avecStockageTemporaire() }
        val jeton = jetonDe(client.post("/compte").bodyAsText())
        val id = client.nouveauDevoir(jeton)

        val rep = client.supprimer("/devoirs/$id", jeton)
        assertEquals(HttpStatusCode.NoContent, rep.status)
        assertFalse(client.get("/devoirs").bodyAsText().contains("Lire le chapitre 3"))
    }

    @Test
    fun `un autre jeton ne peut pas supprimer le devoir d'autrui`() = testApplication {
        application { avecStockageTemporaire() }
        val jetonA = jetonDe(client.post("/compte").bodyAsText())
        val jetonB = jetonDe(client.post("/compte").bodyAsText())
        val id = client.nouveauDevoir(jetonA)

        assertEquals(HttpStatusCode.Forbidden, client.supprimer("/devoirs/$id", jetonB).status)
        assertTrue(client.get("/devoirs").bodyAsText().contains("Lire le chapitre 3"))
    }

    @Test
    fun `supprimer sans jeton est refusé`() = testApplication {
        application { avecStockageTemporaire() }
        val jeton = jetonDe(client.post("/compte").bodyAsText())
        val id = client.nouveauDevoir(jeton)

        assertEquals(HttpStatusCode.Unauthorized, client.supprimer("/devoirs/$id", null).status)
        assertEquals(HttpStatusCode.Unauthorized, client.supprimer("/devoirs/$id", "jeton-inexistant").status)
        assertTrue(client.get("/devoirs").bodyAsText().contains("Lire le chapitre 3"))
    }

    @Test
    fun `supprimer un devoir inconnu renvoie 404`() = testApplication {
        application { avecStockageTemporaire() }
        val jeton = jetonDe(client.post("/compte").bodyAsText())
        assertEquals(HttpStatusCode.NotFound, client.supprimer("/devoirs/999", jeton).status)
        assertEquals(HttpStatusCode.BadRequest, client.supprimer("/devoirs/abc", jeton).status)
    }

    @Test
    fun `le jeton de modération supprime n'importe quel contenu`() = testApplication {
        application { avecStockageTemporaire(jetonAdmin = "clé-de-modération") }
        val jetonA = jetonDe(client.post("/compte").bodyAsText())
        val jetonB = jetonDe(client.post("/compte").bodyAsText())
        val admin = "clé-de-modération"

        val idDevoir = client.nouveauDevoir(jetonA)
        val problème = client.post("/edt/problemes") {
            header("Authorization", "Bearer $jetonA")
            contentType(ContentType.Application.Json)
            setBody("""{"description":"Salle erronée","date":"2026-09-28"}""")
        }.bodyAsText()
        val idProblème = idDe(problème)
        val correction = client.post("/edt/corrections") {
            header("Authorization", "Bearer $jetonB")
            contentType(ContentType.Application.Json)
            setBody("""{"description":"Physique en salle 204","date":"2026-09-28"}""")
        }.bodyAsText()
        val idCorrection = idDe(correction)

        assertEquals(HttpStatusCode.NoContent, client.supprimer("/devoirs/$idDevoir", admin).status)
        assertEquals(HttpStatusCode.NoContent, client.supprimer("/edt/problemes/$idProblème", admin).status)
        assertEquals(HttpStatusCode.NoContent, client.supprimer("/edt/corrections/$idCorrection", admin).status)

        assertFalse(client.get("/devoirs").bodyAsText().contains("Lire le chapitre 3"))
        assertFalse(client.get("/edt/problemes").bodyAsText().contains("Salle erronée"))
        assertFalse(client.get("/edt/corrections").bodyAsText().contains("salle 204"))
    }

    @Test
    fun `une clé de modération ne donne aucun droit si elle n'est pas configurée`() = testApplication {
        application { avecStockageTemporaire() }
        val jetonA = jetonDe(client.post("/compte").bodyAsText())
        val jetonB = jetonDe(client.post("/compte").bodyAsText())
        val id = client.nouveauDevoir(jetonA)

        assertEquals(HttpStatusCode.Unauthorized, client.supprimer("/devoirs/$id", "clé-de-modération").status)
        assertEquals(HttpStatusCode.Forbidden, client.supprimer("/devoirs/$id", jetonB).status)
    }

    @Test
    fun `un auteur peut supprimer son signalement et sa correction d'emploi du temps`() = testApplication {
        application { avecStockageTemporaire() }
        val jeton = jetonDe(client.post("/compte").bodyAsText())
        val problème = client.post("/edt/problemes") {
            header("Authorization", "Bearer $jeton")
            contentType(ContentType.Application.Json)
            setBody("""{"description":"Cours absent","date":"2026-09-28"}""")
        }.bodyAsText()
        val idProblème = idDe(problème)
        val correction = client.post("/edt/corrections") {
            header("Authorization", "Bearer $jeton")
            contentType(ContentType.Application.Json)
            setBody("""{"description":"Cours en salle 12","date":"2026-09-28"}""")
        }.bodyAsText()
        val idCorrection = idDe(correction)

        assertEquals(HttpStatusCode.Forbidden, client.supprimer("/edt/problemes/$idProblème",
            jetonDe(client.post("/compte").bodyAsText())).status)
        assertEquals(HttpStatusCode.NoContent, client.supprimer("/edt/problemes/$idProblème", jeton).status)
        assertEquals(HttpStatusCode.NoContent, client.supprimer("/edt/corrections/$idCorrection", jeton).status)
        assertFalse(client.get("/edt/problemes").bodyAsText().contains("Cours absent"))
        assertFalse(client.get("/edt/corrections").bodyAsText().contains("salle 12"))
    }

    // — Révocation du compte ------------------------------------------------

    @Test
    fun `supprimer son compte révoque le jeton`() = testApplication {
        application { avecStockageTemporaire() }
        val jeton = jetonDe(client.post("/compte").bodyAsText())

        assertEquals(HttpStatusCode.NoContent, client.supprimer("/compte", jeton).status)

        val écriture = client.post("/devoirs") {
            header("Authorization", "Bearer $jeton")
            contentType(ContentType.Application.Json)
            setBody("""{"matière":"SVT","contenu":"Chapitre 3"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, écriture.status, "jeton révoqué, plus aucune écriture")
        assertEquals(HttpStatusCode.Unauthorized, client.supprimer("/compte", jeton).status)
        assertEquals(HttpStatusCode.Unauthorized, client.voter(1, jeton, 1).status)
    }

    @Test
    fun `révoquer un jeton inconnu est refusé`() = testApplication {
        application { avecStockageTemporaire() }
        assertEquals(HttpStatusCode.Unauthorized, client.supprimer("/compte", null).status)
        assertEquals(HttpStatusCode.Unauthorized, client.supprimer("/compte", "jamais-émis").status)
    }

    // — Signalements d'abus -------------------------------------------------

    @Test
    fun `on peut signaler un contenu sans révéler son identité`() = testApplication {
        application { avecStockageTemporaire() }
        val jetonA = jetonDe(client.post("/compte").bodyAsText())
        val jetonB = jetonDe(client.post("/compte").bodyAsText())
        val id = client.nouveauDevoir(jetonA)

        val rep = client.signaler("devoir", id, jetonB, "harcèlement")
        assertEquals(HttpStatusCode.Created, rep.status)
        assertFalse(rep.bodyAsText().contains(jetonB), "le signalant reste secret")

        val liste = client.get("/signalements").bodyAsText()
        assertTrue(liste.contains("harcèlement"))
        assertFalse(liste.contains(jetonB), "le signalant reste secret")
        assertFalse(liste.contains(jetonA))
    }

    @Test
    fun `signaler deux fois le même contenu est refusé`() = testApplication {
        application { avecStockageTemporaire() }
        val jeton = jetonDe(client.post("/compte").bodyAsText())
        val id = client.nouveauDevoir(jeton)

        assertEquals(HttpStatusCode.Created, client.signaler("devoir", id, jeton).status)
        assertEquals(HttpStatusCode.Conflict, client.signaler("devoir", id, jeton).status)
        assertEquals(1, Regex(""""cible":"""").findAll(client.get("/signalements").bodyAsText()).count())
    }

    @Test
    fun `signaler sans jeton valide est refusé`() = testApplication {
        application { avecStockageTemporaire() }
        val jeton = jetonDe(client.post("/compte").bodyAsText())
        val id = client.nouveauDevoir(jeton)

        assertEquals(HttpStatusCode.Unauthorized, client.signaler("devoir", id, null).status)
        assertEquals(HttpStatusCode.Unauthorized, client.signaler("devoir", id, "jeton-inexistant").status)
        assertFalse(client.get("/signalements").bodyAsText().contains("cible"))
    }

    @Test
    fun `signaler une cible inconnue ou invalide est refusé`() = testApplication {
        application { avecStockageTemporaire() }
        val jeton = jetonDe(client.post("/compte").bodyAsText())

        assertEquals(HttpStatusCode.NotFound, client.signaler("devoir", 999, jeton).status)
        assertEquals(HttpStatusCode.NotFound, client.signaler("probleme", 42, jeton).status)
        assertEquals(HttpStatusCode.BadRequest, client.signaler("vote", 1, jeton).status)
        assertEquals(HttpStatusCode.BadRequest, client.signaler("devoir", 1, jeton, "  ").status)
    }

    @Test
    fun `supprimer un contenu purge ses signalements`() = testApplication {
        application { avecStockageTemporaire(jetonAdmin = "clé-de-modération") }
        val jeton = jetonDe(client.post("/compte").bodyAsText())
        val id = client.nouveauDevoir(jeton)

        assertEquals(HttpStatusCode.Created, client.signaler("devoir", id, jeton).status)
        assertEquals(HttpStatusCode.NoContent, client.supprimer("/devoirs/$id", "clé-de-modération").status)
        assertFalse(client.get("/signalements").bodyAsText().contains("cible"))
    }

    @Test
    fun `les signalements survivent à un rechargement`() {
        val s1 = stockageTemporaire()
        val id = s1.id()
        s1.ajouterDevoir(Devoir(id, "auteur-jeton", "Maths", "x", null, 0, 0))
        assertNotNull(s1.signaler("signalant", "devoir", id, "abus"))
        assertNull(s1.signaler("signalant", "devoir", id, "abus"), "un seul signalement par couple")

        val s2 = rouvrir(s1)
        s2.charger()
        assertEquals(1, s2.signalements().size)
        assertEquals("devoir", s2.signalements().single().cible)
        assertTrue(s2.supprimerDevoir(id))
        assertTrue(s2.signalements().isEmpty(), "le signalement suit la suppression de sa cible")

        val s3 = rouvrir(s1)
        s3.charger()
        assertTrue(s3.signalements().isEmpty())
        assertTrue(s3.devoirs(matière = null, depuis = null, "votes", 0, 50).éléments.isEmpty())
    }

    // — CORS -----------------------------------------------------------------

    @Test
    fun `le pré-vol OPTIONS devoirs répond avec les en-têtes CORS`() = testApplication {
        application { avecStockageTemporaire(origines = listOf(ORIGINE)) }
        val rep = client.prévol(ORIGINE)
        assertEquals(HttpStatusCode.OK, rep.status)
        assertEquals(ORIGINE, rep.headers[HttpHeaders.AccessControlAllowOrigin])
        val enTêtes = rep.headers[HttpHeaders.AccessControlAllowHeaders].orEmpty()
            .split(',').map { it.trim().lowercase() }
        assertTrue("authorization" in enTêtes, "Authorization refusé au pré-vol : $enTêtes")
        assertTrue("content-type" in enTêtes, "Content-Type refusé au pré-vol : $enTêtes")
    }

    @Test
    fun `le pré-vol autorise GET, POST et DELETE`() = testApplication {
        application { avecStockageTemporaire(origines = listOf(ORIGINE)) }
        for (méthode in listOf("GET", "POST", "DELETE")) {
            val rep = client.prévol(ORIGINE, méthode)
            assertEquals(HttpStatusCode.OK, rep.status, "$méthode refusé au pré-vol")
        }
    }

    @Test
    fun `une origine absente de GWS_ORIGINS est refusée`() = testApplication {
        application { avecStockageTemporaire(origines = listOf(ORIGINE)) }
        val rep = client.prévol("https://attaquant.example")
        assertEquals(HttpStatusCode.Forbidden, rep.status)
        assertNull(rep.headers[HttpHeaders.AccessControlAllowOrigin])
    }

    @Test
    fun `GWS_ORIGINS accepte plusieurs séparateurs et ignore les vides`() {
        assertEquals(
            listOf("https://a.example", "http://b.example:5173"),
            originesAutorisées("https://a.example , http://b.example:5173/ ;\n\thttps://a.example"),
        )
        assertEquals(emptyList(), originesAutorisées(null), "sans variable, aucune origine")
        assertEquals(emptyList(), originesAutorisées("   "))
    }

    // — Mentions -------------------------------------------------------------

    @Test
    fun `la route mentions est publique et couvre les cinq sections`() = testApplication {
        application { avecStockageTemporaire() }
        val rep = client.get("/mentions")
        assertEquals(HttpStatusCode.OK, rep.status)
        val corps = rep.bodyAsText()
        assertTrue(corps.contains(""""version":"$VERSION_MENTIONS""""))
        for (id in listOf("editeur", "donnees", "usage", "moderation", "droits")) {
            assertTrue(corps.contains(""""id":"$id""""), "section $id absente de $corps")
        }
        assertFalse(corps.contains("\"jeton\":"), "la notice ne référence aucun jeton")
    }

    @Test
    fun `la version des mentions suit le format AAAA-MM-JJ`() =
        assertTrue(Regex("""^\d{4}-\d{2}-\d{2}$""").matches(VERSION_MENTIONS), VERSION_MENTIONS)

    @Test
    fun `créer un compte annonce la version des mentions`() = testApplication {
        application { avecStockageTemporaire() }
        val rep = client.post("/compte")
        val version = Regex(""""mentionsVersion":"([^"]+)"""").find(rep.bodyAsText())!!.groupValues[1]
        assertEquals(VERSION_MENTIONS, version, "le client doit pouvoir invalider son cache")
        assertEquals(version, client.get("/mentions").bodyAsText()
            .let { Regex(""""version":"([^"]+)"""").find(it)!!.groupValues[1] })
    }

    @Test
    fun `le contact de la notice vient de l'environnement, jamais inventé`() {
        val éditeurDe = { c: String? -> mentions(c).sections.first { it.id == "editeur" }.texte }
        assertFalse(éditeurDe(null).contains("Contact"), "aucun contact sans GWS_CONTACT")
        assertFalse(éditeurDe("  ").contains("Contact"), "contact vide ignoré")
        assertTrue(éditeurDe("bureau@greenwood.example").contains("Contact : bureau@greenwood.example"))
    }

    @Test
    fun `la notice rappelle l'essentiel de la confidentialité`() {
        val parId = mentions(null).sections.associateBy { it.id }
        assertTrue(parId.getValue("donnees").texte.contains("Aucun nom"))
        assertTrue(parId.getValue("usage").texte.contains("donnée personnelle"))
        assertTrue(parId.getValue("moderation").texte.contains("suppression est physique"))
    }
}
