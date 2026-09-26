package school.greenwood.community

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.testing.*
import java.io.File
import kotlin.test.*

private fun jetonDe(s: String): String =
    Regex(""""jeton":"([^"]+)"""").find(s)!!.groupValues[1]

class ModuleTest {
    private fun Application.avecStockageTemporaire() =
        module(Stockage(File.createTempFile("test", ".json")))

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
        assertTrue(corps.contains(jeton), "l'auteur est le jeton")
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
        val fichier = File.createTempFile("test", ".json")
        fichier.delete() // Stockage part d'un fichier absent ou vide
        val s1 = Stockage(fichier)
        s1.comptes.add("eagle-smell-bootlace-hypnoses-saddlebag-bunkhouse")
        s1.devoirs.add(Devoir(1, "a", "Maths", "x", null, 0, 0))
        s1.id()
        s1.sauvegarder()
        val s2 = Stockage(fichier)
        s2.charger()
        assertEquals(1, s2.devoirs.size)
        assertTrue(s2.comptes.contains("eagle-smell-bootlace-hypnoses-saddlebag-bunkhouse"))
        fichier.delete()
    }
}
