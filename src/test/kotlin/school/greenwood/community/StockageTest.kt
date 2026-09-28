package school.greenwood.community

import java.util.Collections
import kotlin.concurrent.thread
import kotlin.test.*

/** Écritures concurrentes : SQLite n'admet qu'un écrivain à la fois, le
 *  serveur doit pourtant absorber les requêtes de toute une école. */
class StockageTest {

    @Test
    fun `des écritures concurrentes arrivent toutes à bon port`() {
        val stockage = stockageTemporaire()
        val jetons = (1..8).map { "jeton-$it" }
        jetons.forEach { stockage.créerCompte(it) }

        val échecs = Collections.synchronizedList(mutableListOf<Throwable>())
        val threads = jetons.map { jeton ->
            thread {
                repeat(10) { i ->
                    runCatching {
                        val id = stockage.id()
                        stockage.ajouterDevoir(
                            Devoir(id, jeton, "Maths", "Devoir $i", null, 0, crééÀ = i.toLong()),
                        )
                        stockage.voter(id, jeton, 1)
                    }.onFailure { échecs += it }
                }
            }
        }
        threads.forEach { it.join() }

        assertTrue(échecs.isEmpty(), "écritures perdues : ${échecs.take(3)}")
        val devoirs = stockage.devoirs(matière = null, depuis = null, "votes", 0, 100)
        assertEquals(80, devoirs.total, "aucun devoir n'a été écrasé")
        assertEquals(80, devoirs.éléments.sumOf { it.votes },
            "chaque auteur a voté une fois pour son devoir")
    }

    @Test
    fun `des votes concurrents sur le même devoir restent cohérents`() {
        val stockage = stockageTemporaire()
        val auteur = "auteur-jeton"
        val votants = (1..6).map { "votant-$it" }
        stockage.créerCompte(auteur)
        votants.forEach { stockage.créerCompte(it) }
        val id = stockage.id()
        stockage.ajouterDevoir(Devoir(id, auteur, "SVT", "Chapitre 3", null, 0, crééÀ = 0))

        val votes = votants.mapIndexed { i, jeton -> jeton to if (i % 2 == 0) 1 else -1 }
        val échecs = Collections.synchronizedList(mutableListOf<Throwable>())
        votes.map { (jeton, vote) ->
            thread {
                runCatching { stockage.voter(id, jeton, vote) }.onFailure { échecs += it }
            }
        }.forEach { it.join() }

        assertTrue(échecs.isEmpty(), "votes perdus : $échecs")
        assertEquals(votes.sumOf { it.second }, stockage.devoir(id)!!.votes)
        assertEquals(votes.toMap(), stockage.votes(id))
    }
}
