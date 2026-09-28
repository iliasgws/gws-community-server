package school.greenwood.community

import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.testing.*
import kotlin.test.*;

/** Tri, filtres, pagination et auteurs pseudonymes des listes publiques. */
class ListesTest {

    private fun Application.avecStockageTemporaire() = module(stockageTemporaire())

    /** Les identifiants dans l'ordre de la réponse JSON. */
    private fun idsDe(corps: String): List<Long> =
        Regex(""""id":(\d+)""").findAll(corps).map { it.groupValues[1].toLong() }.toList()

    private fun jetonDe(corps: String): String =
        Regex(""""jeton":"([^"]+)"""").find(corps)!!.groupValues[1]

    /** GET avec paramètres de requête : l'accent de « matière » ou de
     *  « problèmeId » est encodé par le client, jamais écrit à la main. */
    private suspend fun HttpClient.lire(chemin: String, vararg paramètres: Pair<String, String>) =
        get(chemin) { paramètres.forEach { (nom, valeur) -> url.parameters.append(nom, valeur) } }

    private suspend fun HttpClient.nouveauJeton() = jetonDe(post("/compte").bodyAsText())

    private suspend fun HttpClient.devoir(jeton: String, matière: String, contenu: String): Long {
        val rep = post("/devoirs") {
            header("Authorization", "Bearer $jeton")
            contentType(ContentType.Application.Json)
            setBody("""{"matière":"$matière","contenu":"$contenu"}""")
        }
        assertEquals(HttpStatusCode.Created, rep.status)
        return idsDe(rep.bodyAsText()).single()
    }

    private suspend fun HttpClient.voter(id: Long, jeton: String, vote: Int) {
        val rep = post("/devoirs/$id/vote") {
            header("Authorization", "Bearer $jeton")
            contentType(ContentType.Application.Json)
            setBody("""{"vote":$vote}""")
        }
        assertEquals(HttpStatusCode.OK, rep.status)
    }

    private suspend fun HttpClient.problème(jeton: String, description: String, date: String): Long {
        val rep = post("/edt/problemes") {
            header("Authorization", "Bearer $jeton")
            contentType(ContentType.Application.Json)
            setBody("""{"description":"$description","date":"$date"}""")
        }
        assertEquals(HttpStatusCode.Created, rep.status)
        return idsDe(rep.bodyAsText()).single()
    }

    private suspend fun HttpClient.correction(
        jeton: String, problèmeId: Long?, description: String, date: String,
    ): Long {
        val lien = problèmeId?.let { """"problèmeId":$it,""" } ?: ""
        val rep = post("/edt/corrections") {
            header("Authorization", "Bearer $jeton")
            contentType(ContentType.Application.Json)
            setBody("""{$lien"description":"$description","date":"$date"}""")
        }
        assertEquals(HttpStatusCode.Created, rep.status)
        return idsDe(rep.bodyAsText()).single()
    }

    // — Tri des devoirs ----------------------------------------------------

    @Test
    fun `les devoirs les mieux notés viennent en premier`() = testApplication {
        application { avecStockageTemporaire() }
        val auteur = client.nouveauJeton()
        val votant1 = client.nouveauJeton()
        val votant2 = client.nouveauJeton()

        val peuVoté = client.devoir(auteur, "SVT", "Lire le chapitre 3")
        val populaire = client.devoir(auteur, "Maths", "Exercices 1 à 5")
        val moyen = client.devoir(auteur, "Français", "Résumé du texte")

        client.voter(populaire, votant1, 1)
        client.voter(populaire, votant2, 1)
        client.voter(moyen, votant1, 1)

        val rep = client.lire("/devoirs")
        assertEquals(listOf(populaire, moyen, peuVoté), idsDe(rep.bodyAsText()))
        assertEquals("3", rep.headers[HttpHeaders.XTotalCount])
    }

    @Test
    fun `tri=récent remet les nouveautés en tête`() = testApplication {
        application { avecStockageTemporaire() }
        val auteur = client.nouveauJeton()
        val votant = client.nouveauJeton()
        val premier = client.devoir(auteur, "SVT", "Lire le chapitre 3")
        val second = client.devoir(auteur, "Maths", "Exercices 1 à 5")
        val troisième = client.devoir(auteur, "Français", "Résumé du texte")
        client.voter(premier, votant, 1)

        assertEquals(listOf(troisième, second, premier),
            idsDe(client.lire("/devoirs", "tri" to "récent").bodyAsText()))
        assertEquals(listOf(troisième, second, premier),
            idsDe(client.lire("/devoirs", "tri" to "recent").bodyAsText()), "sans accent non plus")
    }

    @Test
    fun `un tri inconnu est refusé`() = testApplication {
        application { avecStockageTemporaire() }
        assertEquals(HttpStatusCode.BadRequest, client.lire("/devoirs", "tri" to "alphabétique").status)
    }

    // — Filtres ------------------------------------------------------------

    @Test
    fun `la matière se filtre sans tenir compte de la casse ni des accents`() = testApplication {
        application { avecStockageTemporaire() }
        val auteur = client.nouveauJeton()
        val maths = client.devoir(auteur, "Maths", "Exercices 1 à 5")
        client.devoir(auteur, "SVT", "Lire le chapitre 3")

        val rep = client.lire("/devoirs", "matière" to "MATHS")
        assertEquals(listOf(maths), idsDe(rep.bodyAsText()))
        assertEquals("1", rep.headers[HttpHeaders.XTotalCount])

        assertEquals(listOf(maths),
            idsDe(client.lire("/devoirs", "matiere" to "maths").bodyAsText()), "nom sans accent accepté")
        assertEquals("0", client.lire("/devoirs", "matière" to "Histoire").headers[HttpHeaders.XTotalCount],
            "aucun résultat, mais le total reste juste")
    }

    @Test
    fun `depuis ne renvoie que les nouveautés`() = testApplication {
        application { avecStockageTemporaire() }
        val auteur = client.nouveauJeton()
        client.devoir(auteur, "Maths", "Devoir ancien")
        val limiteDeTemps = System.currentTimeMillis()
        Thread.sleep(10)
        val récent = client.devoir(auteur, "SVT", "Devoir récent")

        val depuisMillisecondes = client.lire("/devoirs", "depuis" to (limiteDeTemps + 1).toString())
        assertEquals(listOf(récent), idsDe(depuisMillisecondes.bodyAsText()))
        assertEquals("1", depuisMillisecondes.headers[HttpHeaders.XTotalCount])

        val tout = client.lire("/devoirs", "depuis" to "0")
        assertEquals("2", tout.headers[HttpHeaders.XTotalCount])
        assertEquals(2, idsDe(tout.bodyAsText()).size)
    }

    @Test
    fun `depuis accepte les secondes comme les millisecondes`() = testApplication {
        application { avecStockageTemporaire() }
        val auteur = client.nouveauJeton()
        client.devoir(auteur, "Maths", "Devoir 1")
        client.devoir(auteur, "SVT", "Devoir 2")

        // 10^10 secondes = l'an 2286 : ramené en millisecondes, tout est antérieur.
        assertEquals("0", client.lire("/devoirs", "depuis" to "10000000000")
            .headers[HttpHeaders.XTotalCount], "valeur interprétée en secondes")
        // 1,7×10^12 millisecondes = 2023 : les deux devoirs sont postérieurs.
        assertEquals("2", client.lire("/devoirs", "depuis" to "1700000000000")
            .headers[HttpHeaders.XTotalCount], "valeur interprétée en millisecondes")
    }

    @Test
    fun `depuis mal formé est refusé`() = testApplication {
        application { avecStockageTemporaire() }
        assertEquals(HttpStatusCode.BadRequest, client.lire("/devoirs", "depuis" to "hier").status)
    }

    // — Pagination ---------------------------------------------------------

    @Test
    fun `la pagination découpe la liste et annonce le total`() = testApplication {
        application { avecStockageTemporaire() }
        val auteur = client.nouveauJeton()
        val premier = client.devoir(auteur, "Maths", "Devoir 1")
        val deuxième = client.devoir(auteur, "Maths", "Devoir 2")
        val troisième = client.devoir(auteur, "Maths", "Devoir 3")

        val page1 = client.lire("/devoirs", "limite" to "2")
        assertEquals(listOf(troisième, deuxième), idsDe(page1.bodyAsText()))
        assertEquals("3", page1.headers[HttpHeaders.XTotalCount], "le total compte avant pagination")

        val page2 = client.lire("/devoirs", "limite" to "2", "offset" to "2")
        assertEquals(listOf(premier), idsDe(page2.bodyAsText()))
        assertEquals("3", page2.headers[HttpHeaders.XTotalCount])

        val tout = client.lire("/devoirs")
        assertEquals(listOf(troisième, deuxième, premier), idsDe(tout.bodyAsText()))
    }

    @Test
    fun `la page par défaut est bornée à 50 éléments`() {
        val stockage = stockageTemporaire()
        repeat(51) { i ->
            stockage.ajouterDevoir(
                Devoir(stockage.id(), "auteur-jeton", "Maths", "Devoir $i", null, 0, crééÀ = i.toLong()),
            )
        }
        testApplication {
            application { module(stockage) }
            val premièrePage = client.lire("/devoirs")
            assertEquals(50, idsDe(premièrePage.bodyAsText()).size)
            assertEquals("51", premièrePage.headers[HttpHeaders.XTotalCount])
            assertEquals(listOf(1L), idsDe(client.lire("/devoirs", "offset" to "50").bodyAsText()))
        }
    }

    @Test
    fun `des paramètres de pagination invalides sont refusés`() = testApplication {
        application { avecStockageTemporaire() }
        assertEquals(HttpStatusCode.BadRequest, client.lire("/devoirs", "limite" to "vingt").status)
        assertEquals(HttpStatusCode.BadRequest, client.lire("/devoirs", "limite" to "0").status)
        assertEquals(HttpStatusCode.BadRequest, client.lire("/devoirs", "limite" to "9999").status)
        assertEquals(HttpStatusCode.BadRequest, client.lire("/devoirs", "offset" to "-1").status)
        assertEquals(HttpStatusCode.BadRequest, client.lire("/devoirs", "offset" to "1.5").status)
    }

    // — Signalements d'emploi du temps --------------------------------------

    @Test
    fun `les problèmes se filtrent par date et par état`() = testApplication {
        application { avecStockageTemporaire() }
        val auteur = client.nouveauJeton()
        val lundi = client.problème(auteur, "Cours de physique absent", "2026-09-28")
        val mardi = client.problème(auteur, "Salle erronée", "2026-09-29")
        client.correction(auteur, mardi, "Mardi : physique en salle 204", "2026-09-29")

        assertEquals(listOf(lundi), idsDe(client.lire("/edt/problemes", "date" to "2026-09-28").bodyAsText()))
        assertEquals("0", client.lire("/edt/problemes", "date" to "2026-09-30").headers[HttpHeaders.XTotalCount])

        val ouverts = client.lire("/edt/problemes", "etat" to "ouvert")
        assertEquals(listOf(lundi), idsDe(ouverts.bodyAsText()))
        assertEquals("1", ouverts.headers[HttpHeaders.XTotalCount])

        assertEquals(listOf(mardi),
            idsDe(client.lire("/edt/problemes", "état" to "résolu").bodyAsText()))
        assertEquals(listOf(mardi),
            idsDe(client.lire("/edt/problemes", "etat" to "RESOLU").bodyAsText()), "sans accent ni casse")
        assertEquals("0", client.lire("/edt/problemes", "etat" to "ouvert", "date" to "2026-09-29")
            .headers[HttpHeaders.XTotalCount], "le problème de mardi est résolu")
    }

    @Test
    fun `un état inconnu est refusé`() = testApplication {
        application { avecStockageTemporaire() }
        assertEquals(HttpStatusCode.BadRequest, client.lire("/edt/problemes", "etat" to "fini").status)
    }

    @Test
    fun `les problèmes sont les plus récents en premier`() = testApplication {
        application { avecStockageTemporaire() }
        val auteur = client.nouveauJeton()
        val premier = client.problème(auteur, "Salle erronée", "2026-09-28")
        val deuxième = client.problème(auteur, "Cours absent", "2026-09-29")

        assertEquals(listOf(deuxième, premier), idsDe(client.lire("/edt/problemes").bodyAsText()))
    }

    @Test
    fun `les corrections se filtrent par signalement rattaché`() = testApplication {
        application { avecStockageTemporaire() }
        val auteur = client.nouveauJeton()
        val problème = client.problème(auteur, "Salle erronée", "2026-09-28")
        val liée = client.correction(auteur, problème, "Physique en salle 204", "2026-09-28")
        client.correction(auteur, null, "Salle libre le jeudi", "2026-09-30")

        assertEquals(listOf(liée),
            idsDe(client.lire("/edt/corrections", "problèmeId" to problème.toString()).bodyAsText()))
        assertEquals(listOf(liée),
            idsDe(client.lire("/edt/corrections", "problemeId" to problème.toString()).bodyAsText()),
            "nom sans accent accepté")
        assertTrue(
            idsDe(client.lire("/edt/corrections", "problèmeId" to "999").bodyAsText()).isEmpty(),
            "aucune correction n'est rattachée à un signalement inconnu",
        )
        assertEquals("2", client.lire("/edt/corrections").headers[HttpHeaders.XTotalCount])
        assertEquals("0", client.lire("/edt/corrections", "date" to "2026-10-01")
            .headers[HttpHeaders.XTotalCount])
    }

    @Test
    fun `un problèmeId invalide est refusé`() = testApplication {
        application { avecStockageTemporaire() }
        assertEquals(HttpStatusCode.BadRequest,
            client.lire("/edt/corrections", "problèmeId" to "abc").status)
        assertEquals(HttpStatusCode.BadRequest,
            client.lire("/edt/corrections", "problèmeId" to "0").status)
    }

    // — Auteurs pseudonymes -------------------------------------------------

    @Test
    fun `les listes publiques ne portent que l'identifiant pseudonyme de l'auteur`() = testApplication {
        application { avecStockageTemporaire() }
        val auteurA = client.nouveauJeton()
        val auteurB = client.nouveauJeton()
        client.devoir(auteurA, "Maths", "Exercices 1 à 5")
        client.devoir(auteurB, "SVT", "Lire le chapitre 3")
        val problèmeA = client.problème(auteurA, "Salle erronée", "2026-09-28")
        client.correction(auteurA, problèmeA, "Physique en salle 204", "2026-09-28")

        val pseudonyme = Regex(""""auteurId":"([0-9a-f]+)"""")
        for (chemin in listOf("/devoirs", "/edt/problemes", "/edt/corrections")) {
            val corps = client.lire(chemin).bodyAsText()
            assertFalse(corps.contains(auteurA), "$chemin révèle le jeton de l'auteur")
            assertFalse(corps.contains(auteurB), "$chemin révèle le jeton de l'auteur")
            assertTrue(corps.contains(""""auteurId":"""), "$chemin n'expose pas d'auteur pseudonyme")
        }

        val desDevoirs = pseudonyme.findAll(client.lire("/devoirs").bodyAsText())
            .map { it.groupValues[1] }.toSet()
        assertEquals(2, desDevoirs.size, "deux comptes, deux identifiants distincts")
        assertTrue(desDevoirs.all { it.length == 16 }, "identifiant tronqué, jamais le jeton")

        val duProblème = pseudonyme.find(client.lire("/edt/problemes").bodyAsText())!!.groupValues[1]
        val deLaCorrection = pseudonyme.find(client.lire("/edt/corrections").bodyAsText())!!.groupValues[1]
        assertTrue(duProblème in desDevoirs, "le même compte garde le même identifiant partout")
        assertEquals(duProblème, deLaCorrection, "auteur d'un signalement et de sa correction")
    }

    @Test
    fun `l'identifiant pseudonyme est stable et déterministe`() {
        assertEquals(auteurId("eagle-smell-bootlace-hypnoses-saddlebag-bunkhouse"),
            auteurId("eagle-smell-bootlace-hypnoses-saddlebag-bunkhouse"))
        assertNotEquals(auteurId("eagle-smell-bootlace-hypnoses-saddlebag-bunkhouse"),
            auteurId("amber-anchor-apple-arrow-autumn-badge"))
        assertEquals(16, auteurId("n'importe quel jeton").length)
        assertTrue(auteurId("n'importe quel jeton").all { it in "0123456789abcdef" })
    }
}
