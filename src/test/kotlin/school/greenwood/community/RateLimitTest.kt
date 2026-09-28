package school.greenwood.community

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.testing.*
import io.ktor.utils.io.writeString
import kotlin.test.*

/** Limitation de débit : budget par jeton, plafond par IP, budget quotidien
 *  pour POST /compte, et corps bornés à 10 Ko (413) — le serveur ne doit pas
 *  retenir en mémoire un corps qu'il va rejeter. */
class RateLimitTest {

    /** Seuils très hauts : seul le paramètre relevé par un test est abaissé. */
    private fun desLimites(
        écritureParJeton: Int = 999,
        écritureParIP: Int = 999,
        inscriptionParMinute: Int = 999,
        inscriptionParJour: Int = 999,
    ) = Limites(écritureParJeton, écritureParIP, inscriptionParMinute, inscriptionParJour)

    private fun Application.avecLimites(limites: Limites = desLimites()) =
        module(stockageTemporaire(), limites = limites)

    private suspend fun io.ktor.client.HttpClient.inscription(): String {
        val rep = post("/compte")
        assertEquals(HttpStatusCode.Created, rep.status)
        return Regex(""""jeton":"([^"]+)"""").find(rep.bodyAsText())!!.groupValues[1]
    }

    private suspend fun io.ktor.client.HttpClient.posterDevoir(jeton: String, contenu: String = "Lire le chapitre 3") =
        post("/devoirs") {
            header("Authorization", "Bearer $jeton")
            contentType(ContentType.Application.Json)
            setBody("""{"matière":"SVT","contenu":"$contenu"}""")
        }

    @Test
    fun `les lectures ne sont jamais limitées`() = testApplication {
        application { avecLimites() }
        repeat(40) {
            val rep = client.get("/devoirs")
            assertEquals(HttpStatusCode.OK, rep.status)
            assertNull(rep.headers["X-RateLimit-Limit"], "GET /devoirs ne passe pas par le limitateur")
        }
    }

    @Test
    fun `écritures au-delà du budget du jeton refusées en 429`() = testApplication {
        application { avecLimites(desLimites(écritureParJeton = 2)) }
        val jeton = client.inscription()

        assertEquals(HttpStatusCode.Created, client.posterDevoir(jeton).status)
        assertEquals(HttpStatusCode.Created, client.posterDevoir(jeton).status)
        val refus = client.posterDevoir(jeton)
        assertEquals(HttpStatusCode.TooManyRequests, refus.status)
        assertNotNull(refus.headers[HttpHeaders.RetryAfter], "un délai de reprise est renvoyé")
    }

    @Test
    fun `plafond par IP même avec des jetons différents`() = testApplication {
        application { avecLimites(desLimites(écritureParIP = 2)) }
        val a = client.inscription()
        val b = client.inscription()
        val c = client.inscription()

        assertEquals(HttpStatusCode.Created, client.posterDevoir(a).status)
        assertEquals(HttpStatusCode.Created, client.posterDevoir(b).status)
        assertEquals(HttpStatusCode.TooManyRequests, client.posterDevoir(c).status)
    }

    @Test
    fun `inscription en rafale est bornée à la minute`() = testApplication {
        application { avecLimites(desLimites(inscriptionParMinute = 2)) }

        assertEquals(HttpStatusCode.Created, client.post("/compte").status)
        assertEquals(HttpStatusCode.Created, client.post("/compte").status)
        assertEquals(HttpStatusCode.TooManyRequests, client.post("/compte").status)
    }

    @Test
    fun `inscription au-delà du budget quotidien par IP`() = testApplication {
        application { avecLimites(desLimites(inscriptionParJour = 2)) }

        assertEquals(HttpStatusCode.Created, client.post("/compte").status)
        assertEquals(HttpStatusCode.Created, client.post("/compte").status)
        assertEquals(HttpStatusCode.TooManyRequests, client.post("/compte").status)
    }

    @Test
    fun `corps de plus de 10 Ko refusé en 413`() = testApplication {
        application { avecLimites() }
        val jeton = client.inscription()

        val rep = client.posterDevoir(jeton, contenu = "a".repeat(11 * 1024))
        assertEquals(HttpStatusCode.PayloadTooLarge, rep.status)
        assertEquals("[]", client.get("/devoirs").bodyAsText(), "le corps trop gros n'a rien écrit")
    }

    @Test
    fun `corps sans longueur déclarée est accepté s'il tient dans la limite`() = testApplication {
        application { avecLimites() }
        val jeton = client.inscription()
        val json = """{"matière":"SVT","contenu":"Chapitre 3"}"""

        val rep = client.post("/devoirs") {
            header("Authorization", "Bearer $jeton")
            contentType(ContentType.Application.Json)
            setBody(chunked(json))
        }
        assertEquals(HttpStatusCode.Created, rep.status)
        assertTrue(client.get("/devoirs").bodyAsText().contains("Chapitre 3"))
    }

    @Test
    fun `corps chunked de plus de 10 Ko refusé en 413`() = testApplication {
        application { avecLimites() }
        val jeton = client.inscription()
        val json = """{"matière":"SVT","contenu":"${"a".repeat(11 * 1024)}"}"""

        val rep = client.post("/devoirs") {
            header("Authorization", "Bearer $jeton")
            contentType(ContentType.Application.Json)
            setBody(chunked(json))
        }
        assertEquals(HttpStatusCode.PayloadTooLarge, rep.status)
    }

    /** Corps envoyé sans Content-Length : le serveur ne peut pas se fier à
     *  l'en-tête et doit border lui-même la lecture. */
    private fun chunked(texte: String) = object : OutgoingContent.WriteChannelContent() {
        override suspend fun writeTo(ch: io.ktor.utils.io.ByteWriteChannel) {
            ch.writeString(texte)
        }
    }
}
