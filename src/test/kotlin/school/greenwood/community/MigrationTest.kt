package school.greenwood.community

import java.io.File
import java.nio.file.Files
import kotlin.test.*

/** Migration depuis l'ancien stockage fichier JSON : import à la première
 *  ouverture, archivage, et aucun état perdu en silence. */
class MigrationTest {

    /** Un répertoire éphémère contenant un historique JSON. */
    private fun historique(contenu: String? = null): Pair<File, File> {
        val répertoire = Files.createTempDirectory("gws-migration").toFile()
        répertoire.deleteOnExit()
        val fichier = File(répertoire, "communaute.json")
        if (contenu != null) fichier.writeText(contenu)
        return répertoire to fichier
    }

    /** L'état d'une installation JSON d'avant la base de données. */
    private val ÉTAT_JSON = """
        {
            "devoirs": [
                {
                    "id": 1,
                    "auteur": "writer-threat-locust-voter-trace-weekend",
                    "matière": "Histoire",
                    "contenu": "Réviser chapitre 2",
                    "dateRemise": "2026-09-30",
                    "votes": 1,
                    "crééÀ": 1790444912699
                }
            ],
            "comptes": ["writer-threat-locust-voter-trace-weekend"],
            "problèmes": [
                {
                    "id": 2,
                    "auteur": "writer-threat-locust-voter-trace-weekend",
                    "description": "Salle erronée",
                    "date": "2026-09-28",
                    "crééÀ": 1790444912699
                }
            ],
            "corrections": [
                {
                    "id": 3,
                    "auteur": "writer-threat-locust-voter-trace-weekend",
                    "problèmeId": 2,
                    "description": "Physique en salle 204",
                    "date": "2026-09-28",
                    "crééÀ": 1790444912699
                }
            ],
            "signalements": [
                {
                    "id": 4,
                    "auteur": "writer-threat-locust-voter-trace-weekend",
                    "cible": "devoir",
                    "cibleId": 1,
                    "raison": "contenu inapproprié",
                    "crééÀ": 1790444912699
                }
            ],
            "votes": { "1": { "amber-anchor-apple-arrow-autumn-badge": -1 } },
            "idSuivant": 5
        }
    """.trimIndent()

    @Test
    fun `l'historique JSON est importé à la première ouverture puis archivé`() {
        val (répertoire, fichier) = historique(ÉTAT_JSON)
        val stockage = Stockage(fichier)
        stockage.charger()

        assertTrue(stockage.compteExiste("writer-threat-locust-voter-trace-weekend"))
        val devoir = stockage.devoir(1)!!
        assertEquals("Histoire", devoir.matière)
        assertEquals(1, devoir.votes, "le total enregistré est repris tel quel")
        assertEquals(mapOf("amber-anchor-apple-arrow-autumn-badge" to -1), stockage.votes(1))
        assertEquals(1, stockage.devoirs("HISTOIRE", null, "votes", 0, 50).éléments.size,
            "la matière se filtre aussi après migration")
        assertEquals(2L, stockage.problème(2)!!.id)
        assertEquals(listOf(2L),
            stockage.problèmes(null, résolu = true, null, 0, 50).éléments.map { it.problème.id },
            "la correction rattachée résout le signalement")
        assertEquals(1, stockage.corrections(2, null, null, 0, 50).éléments.size)
        assertEquals("devoir", stockage.signalements().single().cible)
        assertEquals(5L, stockage.id(), "la suite d'identifiants reprend à idSuivant")

        assertFalse(fichier.exists(), "l'historique importé quitte son emplacement")
        val archives = File(répertoire, "archives").listFiles()
        assertNotNull(archives, "l'historique est mis en archives")
        assertTrue(archives.any { it.name.startsWith("communaute.json-") },
            "archives : ${archives.toList()}")
    }

    @Test
    fun `une seconde ouverture ne réimporte pas un historique déjà migré`() {
        val (répertoire, fichier) = historique(ÉTAT_JSON)
        Stockage(fichier).charger()

        // Le JSON revient à son emplacement (restauration, copie oubliée…) :
        // la base est déjà peuplée, il ne faut ni le doubler ni le détruire.
        fichier.writeText(ÉTAT_JSON)
        val stockage = Stockage(fichier)
        stockage.charger()

        assertEquals(1, stockage.devoirs(matière = null, depuis = null, "votes", 0, 50)
            .éléments.size, "aucun devoir en double")
        assertTrue(fichier.exists(), "l'historique ignoré reste en place")
        assertEquals(1, File(répertoire, "archives").listFiles()?.size,
            "seule la première migration a déplacé un fichier")
    }

    @Test
    fun `un historique corrompu est laissé en place, jamais avalé`() {
        val (répertoire, fichier) = historique("""{ "devoirs": [ """)
        val stockage = Stockage(fichier)
        stockage.charger()

        assertTrue(fichier.exists(), "un fichier illisible reste à disposition")
        assertTrue(stockage.devoirs(matière = null, depuis = null, "votes", 0, 50)
            .éléments.isEmpty(), "la base reste utilisable")
        assertTrue(File(répertoire, "archives").listFiles().isNullOrEmpty(),
            "rien n'est archivé sans import")
        // Et le serveur peut écrire malgré tout.
        stockage.créerCompte("eagle-smell-bootlace-hypnoses-saddlebag-bunkhouse")
        assertTrue(stockage.compteExiste("eagle-smell-bootlace-hypnoses-saddlebag-bunkhouse"))
    }

    @Test
    fun `GWS_DATA peut nommer la base ou l'ancien fichier JSON`() {
        val (répertoire, _) = historique()
        val parLaBase = Stockage(File(répertoire, "communaute.db"))
        val parLHistorique = Stockage(File(répertoire, "communaute.json"))
        assertEquals(parLaBase.base, parLHistorique.base,
            "les deux chemins désignent la même base voisine")
    }
}
