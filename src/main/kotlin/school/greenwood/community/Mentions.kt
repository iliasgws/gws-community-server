package school.greenwood.community

import kotlinx.serialization.Serializable

// — Mentions légales et politique de confidentialité -------------------------
//
// La notice est servie par le serveur (GET /mentions) plutôt que dupliquée
// dans chaque client : un client qui a la version courante sait que son cache
// est à jour. POST /compte renvoie la même version, ce qui permet d'afficher
// la notice avant la première écriture.

/** Section affichable d'une notice. `id` est stable : le client peut s'en
 *  servir de clé de traduction ou d'ancre. */
@Serializable
data class SectionMentions(val id: String, val titre: String, val texte: String)

@Serializable
data class Mentions(val version: String, val sections: List<SectionMentions>)

/** Dernière mise à jour du texte. À ne changer que si le contenu change :
 *  les clients l'utilisent pour invalider leur copie locale. */
val VERSION_MENTIONS = "2026-09-27"

/** Notice complète. `contact` (variable `GWS_CONTACT`) complète la section
 *  « Éditeur » ; rien n'est inventé lorsqu'il n'est pas défini. */
fun mentions(contact: String? = System.getenv("GWS_CONTACT")): Mentions {
    val éditeur = buildString {
        append("Serveur communautaire de Greenwood School +, entièrement séparé de "
            + "l'API Boti de l'école. Il héberge les contributions des parents et des "
            + "élèves : devoirs suggérés, signalements et corrections d'emploi du temps.")
        if (!contact.isNullOrBlank()) append(" Contact : $contact.")
    }
    return Mentions(
        version = VERSION_MENTIONS,
        sections = listOf(
            SectionMentions("editeur", "Éditeur", éditeur),
            SectionMentions(
                "donnees", "Données collectées",
                "Aucun nom, aucune adresse électronique et aucun mot de passe ne sont "
                    + "demandés. Votre identité est un jeton de six mots tiré au hasard à la "
                    + "création du compte : il est pseudonyme, et vous pouvez en changer en "
                    + "supprimant puis recréant un compte. Les lectures sont publiques, mais "
                    + "le jeton d'un auteur n'apparaît jamais dans les réponses publiques "
                    + "(listes de devoirs, votes, signalements). Les contenus publiés, les "
                    + "votes et les signalements sont conservés dans un fichier JSON de "
                    + "l'hébergement, sans durée de conservation définie. Les journaux "
                    + "techniques du serveur peuvent contenir des informations de connexion.",
            ),
            SectionMentions(
                "usage", "Règles d'usage",
                "Le service est réservé à la vie scolaire : devoirs et emploi du temps. "
                    + "N'y publiez aucune donnée personnelle — nom d'élève, coordonnées, "
                    + "adresse, photo — et aucun propos harcelant, insultant ou usurpé. "
                    + "Tout contenu peut être signalé depuis l'application.",
            ),
            SectionMentions(
                "moderation", "Modération et suppression",
                "La suppression est physique : le contenu, ses votes et ses signalements "
                    + "sont définitivement retirés. Vous pouvez supprimer ce que vous avez "
                    + "publié et révoquer votre jeton à tout moment ; le contenu que vous avez "
                    + "publié reste alors en place, à la charge de la modération. Un jeton de "
                    + "modération peut retirer tout contenu signalé comme abusif.",
            ),
            SectionMentions(
                "droits", "Vos droits",
                "Vous disposez d'un droit d'accès, de rectification et d'effacement sur les "
                    + "données que vous avez publiées. Exercez-le depuis l'application, ou en "
                    + "utilisant le contact indiqué dans la section « Éditeur ». La "
                    + "suppression de votre compte révoque le jeton, qui disparaît du serveur ; "
                    + "le contenu publié reste en place, sans nom ni coordonnée.",
            ),
        ),
    )
}

/** Notice servie par le serveur (contact lu une seule fois au démarrage). */
val MENTIONS: Mentions = mentions()
