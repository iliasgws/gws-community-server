package school.greenwood.community

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

// — Stockage (base SQLite embarquée) ----------------------------------------
//
// Le serveur ne réécrit plus un fichier JSON après chaque écriture : les
// données vivent dans une base SQLite locale (org.xerial:sqlite-jdbc), sans
// service externe, compatible avec le déploiement VPS / systemd du README.
//
// • journal_mode = WAL : une lecture ne bloque plus pendant une écriture, et
//   l'écriture touche la ligne concernée au lieu de réécrire tout l'état ;
// • chaque écriture est une transaction : un vote (ligne + total), une
//   suppression (contenu + votes + signalements) vont ensemble ou pas du tout ;
// • les requêtes (filtres, tri, pagination) sont exécutées par SQLite : la
//   mémoire du serveur ne grandit plus avec l'historique ;
// • l'ancien fichier JSON est importé à la première ouverture, puis archivé —
//   jamais perdu en silence : une migration impossible est un journal d'erreur
//   explicite, et le fichier reste en place pour être récupéré.

/** Page de résultats, avec le nombre total d'éléments correspondant — après
 *  filtres mais avant pagination (en-tête `X-Total-Count`). */
data class Page<T>(val éléments: List<T>, val total: Int)

/** Le chemin configuré et ses deux représentations voisines : la base SQLite
 *  et l'historique JSON à migrer. `GWS_DATA` peut nommer l'un ou l'autre. */
private data class Chemins(val base: File, val historique: File)

private fun cheminsDeStockage(chemin: File): Chemins {
    val absolu = chemin.absoluteFile
    val répertoire = absolu.parentFile
    val nom = absolu.nameWithoutExtension
    return if (absolu.extension == "db") Chemins(absolu, File(répertoire, "$nom.json"))
    else Chemins(File(répertoire, "$nom.db"), absolu)
}

/** Ancien format de sauvegarde, lu uniquement pour la migration. */
@Serializable
private data class ÉtatSauvegardé(
    val devoirs: List<Devoir> = emptyList(),
    val problèmes: List<Problème> = emptyList(),
    val corrections: List<Correction> = emptyList(),
    val comptes: List<String> = emptyList(),
    val signalements: List<Signalement> = emptyList(),
    val votes: Map<Long, Map<String, Int>> = emptyMap(),
    val idSuivant: Long = 1,
)

private const val SCHÉMA = """
CREATE TABLE IF NOT EXISTS comptes (
    jeton TEXT PRIMARY KEY
);
CREATE TABLE IF NOT EXISTS devoirs (
    id INTEGER PRIMARY KEY,
    auteur TEXT NOT NULL,
    matiere TEXT NOT NULL,
    matiere_norm TEXT NOT NULL,
    contenu TEXT NOT NULL,
    date_remise TEXT,
    votes INTEGER NOT NULL DEFAULT 0,
    cree_a INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS votes (
    devoir_id INTEGER NOT NULL,
    jeton TEXT NOT NULL,
    valeur INTEGER NOT NULL,
    PRIMARY KEY (devoir_id, jeton)
);
CREATE TABLE IF NOT EXISTS pieces_jointes (
    id TEXT PRIMARY KEY,
    devoir_id INTEGER NOT NULL,
    nom TEXT NOT NULL,
    type TEXT NOT NULL,
    taille INTEGER NOT NULL,
    stockage TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS problemes (
    id INTEGER PRIMARY KEY,
    auteur TEXT NOT NULL,
    description TEXT NOT NULL,
    date TEXT NOT NULL,
    cree_a INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS corrections (
    id INTEGER PRIMARY KEY,
    auteur TEXT NOT NULL,
    probleme_id INTEGER,
    description TEXT NOT NULL,
    date TEXT NOT NULL,
    cree_a INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS signalements (
    id INTEGER PRIMARY KEY,
    auteur TEXT NOT NULL,
    cible TEXT NOT NULL,
    cible_id INTEGER NOT NULL,
    raison TEXT NOT NULL,
    cree_a INTEGER NOT NULL,
    UNIQUE (auteur, cible, cible_id)
);
CREATE TABLE IF NOT EXISTS identifiants (
    nom TEXT PRIMARY KEY,
    valeur INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_devoirs_tri ON devoirs (votes, cree_a);
CREATE INDEX IF NOT EXISTS idx_corrections_probleme ON corrections (probleme_id);
"""

/** État complet de la communauté : comptes, devoirs, votes, signalements
 *  d'emploi du temps, corrections et signalements d'abus. */
class Stockage(fichier: File) {

    private val chemins = cheminsDeStockage(fichier)
    private val journal = LoggerFactory.getLogger(Stockage::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    private val verrouÉcriture = ReentrantLock()

    /** Base SQLite effectivement utilisée, à côté de l'historique JSON. */
    val base: File get() = chemins.base
    val répertoirePiècesJointes: File get() = File(chemins.base.parentFile, "pieces-jointes")

    fun piècesJointes(devoirId: Long): List<PièceJointePublic> = connection {
        prepareStatement("SELECT id, nom, type, taille FROM pieces_jointes WHERE devoir_id = ? ORDER BY rowid").use {
            it.setLong(1, devoirId)
            it.executeQuery().use { r -> buildList {
                while (r.next()) {
                    val id = r.getString("id")
                    add(PièceJointePublic(id, r.getString("nom"), r.getString("type"), r.getLong("taille"), "/devoirs/$devoirId/pieces-jointes/$id"))
                }
            } }
        }
    }

    fun pièceJointe(devoirId: Long, id: String): PièceJointePublic? = piècesJointes(devoirId).firstOrNull { it.id == id }

    fun ajouterPièceJointe(devoirId: Long, nom: String, type: String, octets: ByteArray): PièceJointePublic? {
        val id = UUID.randomUUID().toString()
        val fichier = File(répertoirePiècesJointes, id)
        répertoirePiècesJointes.mkdirs()
        java.nio.file.Files.write(fichier.toPath(), octets)
        try {
            écriture {
                if (unDevoir(devoirId) == null) return@écriture false
                prepareStatement("INSERT INTO pieces_jointes(id, devoir_id, nom, type, taille, stockage) VALUES(?, ?, ?, ?, ?, ?)").use {
                    it.setString(1, id); it.setLong(2, devoirId); it.setString(3, nom); it.setString(4, type)
                    it.setLong(5, octets.size.toLong()); it.setString(6, id); it.executeUpdate()
                }
                true
            }.also { if (!it) fichier.delete() }
        } catch (e: Throwable) { fichier.delete(); throw e }
        return pièceJointe(devoirId, id)
    }

    fun fichierPièceJointe(id: String): File? = connection {
        prepareStatement("SELECT stockage FROM pieces_jointes WHERE id = ?").use {
            it.setString(1, id)
            it.executeQuery().use { r -> if (r.next()) File(répertoirePiècesJointes, r.getString(1)).takeIf(File::isFile) else null }
        }
    }

    init {
        schéma()
    }

    // — Connexions ---------------------------------------------------------

    /** Une connexion par opération : rien n'est gardé ouvert entre deux
     *  requêtes, chaque thread lit et écrit sur la sienne. */
    private fun <T> connection(opération: Connection.() -> T): T =
        DriverManager.getConnection("jdbc:sqlite:${chemins.base.path}").use { c ->
            c.createStatement().use { it.execute("PRAGMA busy_timeout = 5000") }
            c.opération()
        }

    /** Transaction : tout l'opération est validé d'un trait, ou rien.
     *
     *  SQLite n'admet qu'un seul écrivain à la fois : les écritures sont
     *  serialisées ici, ce qui évite tout « database is locked » entre deux
     *  requêtes du serveur. Les lectures ne passent pas par ce verrou — WAL
     *  les laisse courir pendant une écriture. */
    private fun <T> écriture(opération: Connection.() -> T): T = verrouÉcriture.withLock {
        connection {
            autoCommit = false
            try {
                val résultat = opération()
                commit()
                résultat
            } catch (e: Throwable) {
                runCatching { rollback() }
                throw e
            }
        }
    }

    private fun schéma() = connection {
        createStatement().use { déclaration ->
            // WAL est persistant dans le fichier : il ne faut le poser qu'une fois.
            déclaration.execute("PRAGMA journal_mode = WAL")
            SCHÉMA.split(';')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .forEach { déclaration.executeUpdate(it) }
            déclaration.executeUpdate(
                "INSERT OR IGNORE INTO identifiants(nom, valeur) VALUES('id', 1)")
        }
    }

    // — Identifiants -------------------------------------------------------

    /** Identifiant suivant, partagé par tous les contenus : comme autrefois,
     *  devoirs, problèmes, corrections et signalements puisent dans une seule
     *  suite, jamais réutilisée. */
    fun id(): Long = écriture { prochainId() }

    private fun Connection.prochainId(): Long {
        val actuel = prepareStatement("SELECT valeur FROM identifiants WHERE nom = 'id'").use {
            it.executeQuery().use { r -> if (r.next()) r.getLong(1) else 1L }
        }
        prepareStatement("UPDATE identifiants SET valeur = ? WHERE nom = 'id'").use {
            it.setLong(1, actuel + 1)
            it.executeUpdate()
        }
        return actuel
    }

    // — Comptes ------------------------------------------------------------

    fun créerCompte(jeton: String) {
        écriture {
            prepareStatement("INSERT INTO comptes(jeton) VALUES(?)").use {
                it.setString(1, jeton)
                it.executeUpdate()
            }
        }
    }

    /** Un jeton émis par le serveur et non révoqué. */
    fun compteExiste(jeton: String): Boolean = connection {
        prepareStatement("SELECT 1 FROM comptes WHERE jeton = ?").use {
            it.setString(1, jeton)
            it.executeQuery().use { r -> r.next() }
        }
    }

    /** Révoque un jeton : il ne peut plus rien écrire, mais son contenu reste. */
    fun révoquerJeton(jeton: String): Boolean = écriture {
        prepareStatement("DELETE FROM comptes WHERE jeton = ?").use {
            it.setString(1, jeton)
            it.executeUpdate() > 0
        }
    }

    // — Devoirs ------------------------------------------------------------

    fun ajouterDevoir(devoir: Devoir) {
        écriture { insérer(devoir) }
    }

    private fun Connection.insérer(devoir: Devoir) {
        prepareStatement(
            "INSERT INTO devoirs(id, auteur, matiere, matiere_norm, contenu, date_remise, " +
                "votes, cree_a) VALUES(?, ?, ?, ?, ?, ?, ?, ?)",
        ).use {
            it.setLong(1, devoir.id)
            it.setString(2, devoir.auteur)
            it.setString(3, devoir.matière)
            it.setString(4, devoir.matière.normalisé())
            it.setString(5, devoir.contenu)
            it.setObject(6, devoir.dateRemise)
            it.setInt(7, devoir.votes)
            it.setLong(8, devoir.crééÀ)
            it.executeUpdate()
        }
    }

    fun devoir(id: Long): Devoir? = connection { unDevoir(id) }

    private fun Connection.unDevoir(id: Long): Devoir? =
        prepareStatement(
            "SELECT id, auteur, matiere, contenu, date_remise, votes, cree_a " +
                "FROM devoirs WHERE id = ?",
        ).use {
            it.setLong(1, id)
            it.executeQuery().use { r -> if (r.next()) r.devoir() else null }
        }

    /** Page de devoirs : filtre (matière sans casse ni accent, nouveautés) et
     *  tri (votes décroissants ou plus récents d'abord) délégués à SQLite. */
    fun devoirs(
        matière: String?,
        depuis: Long?,
        tri: String,
        offset: Int,
        limite: Int,
    ): Page<Devoir> = connection {
        val conditions = mutableListOf<String>()
        val valeurs = mutableListOf<Any>()
        if (matière != null) {
            conditions += "matiere_norm = ?"
            valeurs += matière.normalisé()
        }
        if (depuis != null) {
            conditions += "cree_a >= ?"
            valeurs += depuis
        }
        val filtre = conditions.clauses()
        val ordre = when (tri) {
            "votes" -> "votes DESC, cree_a DESC, id DESC"
            else -> "cree_a DESC, id DESC"
        }
        val total = prepareStatement("SELECT COUNT(*) FROM devoirs$filtre").use {
            it.liaisons(valeurs)
            it.executeQuery().use { r -> r.next(); r.getInt(1) }
        }
        val page = prepareStatement(
            "SELECT id, auteur, matiere, contenu, date_remise, votes, cree_a " +
                "FROM devoirs$filtre ORDER BY $ordre LIMIT ? OFFSET ?",
        ).use {
            it.liaisons(valeurs + limite + offset)
            it.executeQuery().use { r -> buildList { while (r.next()) add(r.devoir()) } }
        }
        Page(page, total)
    }

    /** Enregistre le vote d'un jeton sur un devoir — total et ligne de vote dans
     *  la même transaction — et renvoie le devoir mis à jour, ou null s'il a
     *  disparu. */
    fun voter(devoirId: Long, jeton: String, vote: Int): Devoir? = écriture {
        val précédent = prepareStatement(
            "SELECT valeur FROM votes WHERE devoir_id = ? AND jeton = ?",
        ).use {
            it.setLong(1, devoirId)
            it.setString(2, jeton)
            it.executeQuery().use { r -> if (r.next()) r.getInt(1) else 0 }
        }
        prepareStatement("UPDATE devoirs SET votes = votes + ? WHERE id = ?").use {
            it.setInt(1, vote - précédent)
            it.setLong(2, devoirId)
            if (it.executeUpdate() == 0) return@écriture null
        }
        prepareStatement(
            "INSERT INTO votes(devoir_id, jeton, valeur) VALUES(?, ?, ?) " +
                "ON CONFLICT(devoir_id, jeton) DO UPDATE SET valeur = excluded.valeur",
        ).use {
            it.setLong(1, devoirId)
            it.setString(2, jeton)
            it.setInt(3, vote)
            it.executeUpdate()
        }
        unDevoir(devoirId)
    }

    /** Votes d'un devoir : jeton → valeur (+1 ou −1). */
    fun votes(devoirId: Long): Map<String, Int> = connection {
        prepareStatement("SELECT jeton, valeur FROM votes WHERE devoir_id = ?").use {
            it.setLong(1, devoirId)
            it.executeQuery().use { r ->
                buildMap { while (r.next()) put(r.getString(1), r.getInt(2)) }
            }
        }
    }

    /** Suppression physique d'un devoir : contenu, votes et signalements dans
     *  une seule transaction. Renvoie false si absent. */
    fun supprimerDevoir(id: Long): Boolean {
        val (supprimé, fichiers) = écriture {
            val pièces = prepareStatement("SELECT stockage FROM pieces_jointes WHERE devoir_id = ?").use {
                it.setLong(1, id)
                it.executeQuery().use { r -> buildList { while (r.next()) add(r.getString(1)) } }
            }
            val supprimé = prepareStatement("DELETE FROM devoirs WHERE id = ?").use {
                it.setLong(1, id)
                it.executeUpdate() > 0
            }
            if (supprimé) {
                prepareStatement("DELETE FROM pieces_jointes WHERE devoir_id = ?").use {
                    it.setLong(1, id)
                    it.executeUpdate()
                }
                prepareStatement("DELETE FROM votes WHERE devoir_id = ?").use {
                    it.setLong(1, id)
                    it.executeUpdate()
                }
                purgerSignalements("devoir", id)
            }
            supprimé to if (supprimé) pièces else emptyList()
        }
        fichiers.forEach { File(répertoirePiècesJointes, it).delete() }
        return supprimé
    }

    // — Signalements d'emploi du temps -------------------------------------

    fun ajouterProblème(problème: Problème) {
        écriture {
            prepareStatement(
                "INSERT INTO problemes(id, auteur, description, date, cree_a) " +
                    "VALUES(?, ?, ?, ?, ?)",
            ).use {
                it.setLong(1, problème.id)
                it.setString(2, problème.auteur)
                it.setString(3, problème.description)
                it.setString(4, problème.date)
                it.setLong(5, problème.crééÀ)
                it.executeUpdate()
            }
        }
    }

    fun problème(id: Long): Problème? = connection {
        prepareStatement(
            "SELECT id, auteur, description, date, cree_a FROM problemes WHERE id = ?",
        ).use {
            it.setLong(1, id)
            it.executeQuery().use { r -> if (r.next()) r.problème() else null }
        }
    }

    /** Page de signalements ; l'état (« résolu » dès qu'une correction est
     *  rattachée) est calculé par SQLite, jamais stocké. */
    fun problèmes(
        date: String?,
        résolu: Boolean?,
        depuis: Long?,
        offset: Int,
        limite: Int,
    ): Page<ProblèmeAvecÉtat> = connection {
        val conditions = mutableListOf<String>()
        val valeurs = mutableListOf<Any>()
        if (date != null) {
            conditions += "p.date = ?"
            valeurs += date
        }
        if (depuis != null) {
            conditions += "p.cree_a >= ?"
            valeurs += depuis
        }
        if (résolu == true) conditions += RÉSOLUTION
        if (résolu == false) conditions += "NOT $RÉSOLUTION"
        val filtre = conditions.clauses()
        val total = prepareStatement("SELECT COUNT(*) FROM problemes p$filtre").use {
            it.liaisons(valeurs)
            it.executeQuery().use { r -> r.next(); r.getInt(1) }
        }
        val page = prepareStatement(
            "SELECT p.id, p.auteur, p.description, p.date, p.cree_a, $RÉSOLUTION AS resolu " +
                "FROM problemes p$filtre ORDER BY p.cree_a DESC, p.id DESC LIMIT ? OFFSET ?",
        ).use {
            it.liaisons(valeurs + limite + offset)
            it.executeQuery().use { r ->
                buildList {
                    while (r.next()) add(ProblèmeAvecÉtat(r.problème(), r.getBoolean("resolu")))
                }
            }
        }
        Page(page, total)
    }

    /** Suppression physique d'un signalement : ses signalements d'abus suivent,
     *  les corrections rattachées restent en place, comme autrefois. */
    fun supprimerProblème(id: Long): Boolean = écriture {
        val supprimé = prepareStatement("DELETE FROM problemes WHERE id = ?").use {
            it.setLong(1, id)
            it.executeUpdate() > 0
        }
        if (supprimé) purgerSignalements("probleme", id)
        supprimé
    }

    // — Corrections d'emploi du temps --------------------------------------

    fun ajouterCorrection(correction: Correction) {
        écriture {
            prepareStatement(
                "INSERT INTO corrections(id, auteur, probleme_id, description, date, cree_a) " +
                    "VALUES(?, ?, ?, ?, ?, ?)",
            ).use {
                it.setLong(1, correction.id)
                it.setString(2, correction.auteur)
                it.setObject(3, correction.problèmeId)
                it.setString(4, correction.description)
                it.setString(5, correction.date)
                it.setLong(6, correction.crééÀ)
                it.executeUpdate()
            }
        }
    }

    fun correction(id: Long): Correction? = connection {
        prepareStatement(
            "SELECT id, auteur, probleme_id, description, date, cree_a " +
                "FROM corrections WHERE id = ?",
        ).use {
            it.setLong(1, id)
            it.executeQuery().use { r -> if (r.next()) r.correction() else null }
        }
    }

    fun corrections(
        problèmeId: Long?,
        date: String?,
        depuis: Long?,
        offset: Int,
        limite: Int,
    ): Page<Correction> = connection {
        val conditions = mutableListOf<String>()
        val valeurs = mutableListOf<Any>()
        if (problèmeId != null) {
            conditions += "c.probleme_id = ?"
            valeurs += problèmeId
        }
        if (date != null) {
            conditions += "c.date = ?"
            valeurs += date
        }
        if (depuis != null) {
            conditions += "c.cree_a >= ?"
            valeurs += depuis
        }
        val filtre = conditions.clauses()
        val total = prepareStatement("SELECT COUNT(*) FROM corrections c$filtre").use {
            it.liaisons(valeurs)
            it.executeQuery().use { r -> r.next(); r.getInt(1) }
        }
        val page = prepareStatement(
            "SELECT c.id, c.auteur, c.probleme_id, c.description, c.date, c.cree_a " +
                "FROM corrections c$filtre ORDER BY c.cree_a DESC, c.id DESC LIMIT ? OFFSET ?",
        ).use {
            it.liaisons(valeurs + limite + offset)
            it.executeQuery().use { r -> buildList { while (r.next()) add(r.correction()) } }
        }
        Page(page, total)
    }

    fun supprimerCorrection(id: Long): Boolean = écriture {
        val supprimé = prepareStatement("DELETE FROM corrections WHERE id = ?").use {
            it.setLong(1, id)
            it.executeUpdate() > 0
        }
        if (supprimé) purgerSignalements("correction", id)
        supprimé
    }

    // — Signalements d'abus -------------------------------------------------

    /** Enregistre un signalement ; renvoie null s'il existe déjà pour ce couple
     *  (auteur, cible). La transaction est annulée : l'identifiant n'est pas
     *  consommé. */
    fun signaler(auteur: String, cible: String, cibleId: Long, raison: String): Signalement? =
        écriture {
            val signalement = Signalement(
                prochainId(), auteur, cible, cibleId, raison, System.currentTimeMillis(),
            )
            val inséré = prepareStatement(
                "INSERT INTO signalements(id, auteur, cible, cible_id, raison, cree_a) " +
                    "VALUES(?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING",
            ).use {
                it.setLong(1, signalement.id)
                it.setString(2, signalement.auteur)
                it.setString(3, signalement.cible)
                it.setLong(4, signalement.cibleId)
                it.setString(5, signalement.raison)
                it.setLong(6, signalement.crééÀ)
                it.executeUpdate() > 0
            }
            if (inséré) signalement else null
        }

    fun signalements(): List<Signalement> = connection {
        createStatement().use { st ->
            st.executeQuery(
                "SELECT id, auteur, cible, cible_id, raison, cree_a " +
                    "FROM signalements ORDER BY id",
            ).use { r -> buildList { while (r.next()) add(r.signalement()) } }
        }
    }

    /** Un signalement dont la cible disparaît n'a plus de sens. */
    private fun Connection.purgerSignalements(cible: String, cibleId: Long) {
        prepareStatement("DELETE FROM signalements WHERE cible = ? AND cible_id = ?").use {
            it.setString(1, cible)
            it.setLong(2, cibleId)
            it.executeUpdate()
        }
    }

    // — Migration depuis l'ancien fichier JSON ------------------------------

    /** Importe l'historique JSON à la première ouverture, puis l'archive.
     *  Idempotent : sans fichier, ou avec une base déjà peuplée, il ne se passe
     *  rien. Un fichier illisible est journalisé en erreur et laissé en place —
     *  jamais de perte silencieuse. */
    fun charger() {
        val source = chemins.historique
        if (!source.exists() || source.length() == 0L) return
        if (!vide()) {
            journal.warn("Base déjà peuplée : {} est laissé en place sans être importé", source)
            return
        }
        val état = try {
            json.decodeFromString<ÉtatSauvegardé>(source.readText())
        } catch (e: Exception) {
            journal.error("Migration impossible : {} est illisible ({})", source, e.message, e)
            return
        }
        try {
            écriture { importer(état) }
        } catch (e: Exception) {
            journal.error("Migration impossible : {} n'a pas pu être importé ({})", source, e.message, e)
            return
        }
        archiver(source)
    }

    /** La base ne contient encore aucune ligne. */
    private fun vide(): Boolean = connection {
        listOf("comptes", "devoirs", "problemes", "corrections", "signalements").all { table ->
            createStatement().use { st ->
                st.executeQuery("SELECT COUNT(*) FROM $table").use { r -> r.next(); r.getInt(1) == 0 }
            }
        }
    }

    private fun Connection.importer(état: ÉtatSauvegardé) {
        prepareStatement("INSERT INTO comptes(jeton) VALUES(?)").use { st ->
            état.comptes.forEach { jeton ->
                st.setString(1, jeton)
                st.addBatch()
            }
            st.executeBatch()
        }
        état.devoirs.forEach { insérer(it) }
        prepareStatement("INSERT INTO votes(devoir_id, jeton, valeur) VALUES(?, ?, ?)").use { st ->
            état.votes.forEach { (devoirId, parJeton) ->
                parJeton.forEach { (jeton, valeur) ->
                    st.setLong(1, devoirId)
                    st.setString(2, jeton)
                    st.setInt(3, valeur)
                    st.addBatch()
                }
            }
            st.executeBatch()
        }
        prepareStatement(
            "INSERT INTO problemes(id, auteur, description, date, cree_a) VALUES(?, ?, ?, ?, ?)",
        ).use { st ->
            état.problèmes.forEach { problème ->
                st.setLong(1, problème.id)
                st.setString(2, problème.auteur)
                st.setString(3, problème.description)
                st.setString(4, problème.date)
                st.setLong(5, problème.crééÀ)
                st.addBatch()
            }
            st.executeBatch()
        }
        prepareStatement(
            "INSERT INTO corrections(id, auteur, probleme_id, description, date, cree_a) " +
                "VALUES(?, ?, ?, ?, ?, ?)",
        ).use { st ->
            état.corrections.forEach { correction ->
                st.setLong(1, correction.id)
                st.setString(2, correction.auteur)
                st.setObject(3, correction.problèmeId)
                st.setString(4, correction.description)
                st.setString(5, correction.date)
                st.setLong(6, correction.crééÀ)
                st.addBatch()
            }
            st.executeBatch()
        }
        prepareStatement(
            "INSERT INTO signalements(id, auteur, cible, cible_id, raison, cree_a) " +
                "VALUES(?, ?, ?, ?, ?, ?)",
        ).use { st ->
            état.signalements.forEach { signalement ->
                st.setLong(1, signalement.id)
                st.setString(2, signalement.auteur)
                st.setString(3, signalement.cible)
                st.setLong(4, signalement.cibleId)
                st.setString(5, signalement.raison)
                st.setLong(6, signalement.crééÀ)
                st.addBatch()
            }
            st.executeBatch()
        }
        // Jamais d'identifiant déjà pris, même si l'historique est incohérent.
        val occupés = état.devoirs.map { it.id } + état.problèmes.map { it.id } +
            état.corrections.map { it.id } + état.signalements.map { it.id }
        val prochain = maxOf(état.idSuivant, (occupés.maxOrNull() ?: 0L) + 1L)
        prepareStatement("UPDATE identifiants SET valeur = ? WHERE nom = 'id'").use {
            it.setLong(1, prochain)
            it.executeUpdate()
        }
    }

    /** Déplace l'historique importé dans un sous-répertoire `archives/`, à côté
     *  de la base. */
    private fun archiver(source: File) {
        val répertoire = File(source.parentFile, "archives")
        répertoire.mkdirs()
        val horodatage = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"))
        val cible = File(répertoire, "${source.name}-$horodatage")
        if (source.renameTo(cible)) {
            journal.info("Historique JSON importé puis archivé : {}", cible)
        } else {
            journal.warn("Import réussi mais {} n'a pas pu être archivé dans {}", source, répertoire)
        }
    }

    // — Lecture des lignes -------------------------------------------------

    private fun ResultSet.devoir() = Devoir(
        getLong("id"), getString("auteur"), getString("matiere"), getString("contenu"),
        getString("date_remise"), getInt("votes"), getLong("cree_a"),
    )

    private fun ResultSet.problème() = Problème(
        getLong("id"), getString("auteur"), getString("description"), getString("date"),
        getLong("cree_a"),
    )

    private fun ResultSet.correction() = Correction(
        getLong("id"), getString("auteur"), getObject("probleme_id")?.let { getLong("probleme_id") },
        getString("description"), getString("date"), getLong("cree_a"),
    )

    private fun ResultSet.signalement() = Signalement(
        getLong("id"), getString("auteur"), getString("cible"), getLong("cible_id"),
        getString("raison"), getLong("cree_a"),
    )

    private companion object {
        /** Une correction rattachée résout le signalement qu'elle cite. */
        const val RÉSOLUTION = "EXISTS(SELECT 1 FROM corrections c WHERE c.probleme_id = p.id)"
    }
}

/** `WHERE … AND …` à partir de conditions déjà formulées, ou rien. */
private fun MutableList<String>.clauses(): String =
    if (isEmpty()) "" else " WHERE " + joinToString(" AND ")

private fun PreparedStatement.liaisons(valeurs: List<Any>) {
    valeurs.forEachIndexed { i, valeur -> setObject(i + 1, valeur) }
}
