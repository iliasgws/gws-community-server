package school.greenwood.community

import java.io.File

/** Un Stockage éphémère pour les tests : le fichier JSON historique est absent
 *  (aucune migration à faire) et la base SQLite voisine vit dans le répertoire
 *  temporaire, nettoyée en fin de session. */
fun stockageTemporaire(): Stockage {
    val fichier = File.createTempFile("gws-test", ".json")
    fichier.delete()
    return Stockage(fichier).also { stockage ->
        listOf(File("${stockage.base}-wal"), File("${stockage.base}-shm"), stockage.base)
            .forEach { it.deleteOnExit() }
    }
}

/** Une seconde base ouverte sur le même fichier, pour vérifier la
 *  persistance. */
fun rouvrir(stockage: Stockage) = Stockage(stockage.base)
