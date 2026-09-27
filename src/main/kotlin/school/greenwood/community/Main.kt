package school.greenwood.community

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.receive
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

// — Comptes à jetons de mots (« eagle-smell-bootlace-… ») --------------------

/** Mots courts, sans accent, parmi lesquels le jeton est tiré. La liste est
 *  volontairement petite (243 = 3^5) : six mots donnent 3^30 ≈ 2×10^14
 *  combinaisons, largement assez pour l'école tout en restant mémorisable. */
val MOTS = listOf(
    "eagle", "smell", "bootlace", "hypnoses", "saddlebag", "bunkhouse",
    "amber", "anchor", "apple", "arrow", "autumn", "badge", "bagpipe", "baker",
    "bamboo", "banner", "barn", "basin", "beacon", "beaver", "beetle", "bell",
    "bench", "berry", "bike", "birch", "bishop", "bison", "blade", "blanket",
    "blossom", "board", "bobcat", "bonfire", "borax", "bottle", "boulder",
    "bounce", "brave", "bread", "bridge", "bronze", "brook", "brush", "bucket",
    "buffalo", "bugle", "bundle", "burrow", "butter", "cabin", "cable", "cactus",
    "camel", "camera", "campus", "candle", "canoe", "canvas", "canyon", "cargo",
    "carpet", "carrot", "castle", "cattle", "cedar", "cello", "chalk", "cherry",
    "chess", "chief", "chili", "chimney", "chipmunk", "cider", "cinema", "circle",
    "citrus", "clover", "cobalt", "comet", "compass", "copper", "coral", "cotton",
    "cougar", "crayon", "creek", "cricket", "crisp", "crown", "crystal", "cupola",
    "curtain", "daisy", "damper", "dandy", "dawn", "deck", "delta", "denim",
    "desert", "dewdrop", "diamond", "diesel", "dingo", "dolphin", "domino",
    "donkey", "dove", "dragon", "drawer", "drift", "drum", "duck", "dune",
    "dwarf", "easel", "ember", "engine", "escrow", "fable", "falcon", "fanfare",
    "farm", "feather", "fence", "fern", "fiddle", "fig", "filter", "finch",
    "finger", "fjord", "flag", "flame", "flamingo", "flannel", "flask", "fleet",
    "flint", "flower", "flute", "foam", "forest", "forge", "fossil", "fountain",
    "fox", "freight", "frost", "fruit", "furnace", "gadget", "galaxy", "gallery",
    "garlic", "gazelle", "gecko", "geyser", "ginger", "glacier", "glass",
    "glider", "glove", "goat", "gold", "gopher", "granite", "grape", "gravel",
    "green", "grove", "guitar", "gully", "gum", "gust", "gym", "hall", "hamlet",
    "hammer", "hammock", "harbor", "harmony", "harvest", "hawk", "hazel",
    "heather", "hedge", "helmet", "heron", "hexagon", "hickory", "hill",
    "hinge", "hobby", "hollow", "honey", "hood", "hornet", "horizon", "hurdle",
    "husk", "igloo", "impala", "indent", "ingot", "inlet", "iris", "iron",
    "island", "ivory", "ivy", "jacket", "jade", "jaguar", "jasmine", "jasper",
    "jetty", "jewel", "jigsaw", "jingle", "jolly", "jotter", "joule", "joyful",
    "jumble", "jungle", "juniper", "kayak", "kernel", "keystone", "kettle",
    "keyboard", "kilt", "kimono", "kindle", "kingfisher", "kiosk", "kitten",
    "kiwi", "knapsack", "knight", "koala", "krill", "label", "ladder", "ladle",
    "lagoon", "lake", "lamp", "lantern", "larch", "laser", "lattice", "laurel",
    "lavender", "leaf", "ledger", "legend", "lemon", "lens", "leopard", "lever",
    "lichen", "lilac", "lily", "limber", "linen", "lion", "lizard", "llama",
    "loaf", "lobster", "locket", "locust", "loft", "log", "loop", "lotus",
    "lumber", "lunar", "lupin", "lynx", "lyre", "macaw", "magnet", "mahogany",
    "maize", "mallet", "mango", "mantis", "maple", "marble", "marginal",
    "marlin", "marsh", "mascot", "mason", "mastiff", "meadow", "medal",
    "melody", "melon", "mentor", "mercury", "mesa", "meteor", "mimosa",
    "mineral", "mint", "mirror", "mist", "mitten", "moat", "model", "mole",
    "monarch", "monsoon", "moral", "morsel", "mosaic", "moss", "moth",
    "motif", "mountain", "mouse", "muffin", "mulberry", "mural", "musket",
    "mustard", "myrtle", "napkin", "narrow", "nectar", "needle", "nest",
    "nettle", "nickel", "nimble", "noble", "nomad", "noodle", "north",
    "notch", "nugget", "nutmeg", "oak", "oasis", "oat", "oblong", "ocelot",
    "octave", "octopus", "ogle", "oil", "olive", "onyx", "opal", "orbit",
    "orchard", "orchid", "oregano", "osprey", "ostrich", "otter", "ounce",
    "outfit", "oval", "oven", "owl", "oxide", "oyster", "paddle", "pagoda",
    "painter", "palace", "palm", "pancake", "panther", "papaya", "paprika",
    "papyrus", "parade", "parcel", "park", "parsley", "pastel", "pasture",
    "pattern", "peach", "peacock", "peanut", "pearl", "pebble", "pelican",
    "pencil", "penguin", "peony", "pepper", "perfume", "petal", "pewter",
    "phantom", "phone", "photo", "piano", "pickle", "pigeon", "pigment",
    "pillar", "pillow", "pilot", "pine", "pinto", "pioneer", "pistol",
    "pivot", "pixel", "plaid", "planet", "plank", "plant", "plateau",
    "plaza", "pledge", "plum", "plume", "plunder", "pocket", "podium",
    "polar", "polka", "pomelo", "poncho", "poplar", "poppy", "porch",
    "portal", "postage", "potato", "pouch", "powder", "prairie", "pretzel",
    "prism", "profile", "promenade", "prune", "puddle", "puffin", "pulpit",
    "pumpkin", "puppet", "purpose", "puzzle", "pyramid", "qualm", "quarry",
    "quartz", "queen", "quench", "quibble", "quilt", "quince", "quinine",
    "quirk", "quiver", "quorum", "rabbit", "raccoon", "radish", "rafter",
    "rail", "rainbow", "raisin", "rampart", "ranch", "random", "ranger",
    "raspberry", "ratatouille", "raven", "ravine", "razor", "rebel",
    "redwood", "reef", "relic", "remnant", "rendezvous", "reptile", "resin",
    "rhubarb", "ribbon", "ridge", "rifle", "rincon", "ripple", "riser",
    "rivet", "roadrunner", "roast", "robin", "rocket", "rodeo", "rogue",
    "roman", "rooster", "root", "rope", "rose", "roster", "rugged", "ruin",
    "rumble", "runner", "rural", "russet", "rustic", "saddle", "safari",
    "saffron", "sage", "sailboat", "salad", "salmon", "salsa", "salt",
    "salute", "sample", "sandal", "sapphire", "sardine", "sash", "satin",
    "saucepan", "scaffold", "scallop", "scepter", "scheme", "scholar",
    "school", "scoop", "scorpion", "scout", "scrapbook", "screen", "screw",
    "script", "scroll", "sculpture", "seagull", "seal", "season", "sequin",
    "sequoia", "sermon", "sesame", "shallow", "shamrock", "shark", "shawl",
    "sheep", "sheet", "shelf", "sheriff", "shimmer", "ship", "shrimp",
    "shrub", "shutter", "sierra", "siesta", "signal", "silica", "silk",
    "silhouette", "silo", "silver", "siren", "sketch", "skillet", "sky",
    "slate", "sled", "sleeve", "slalom", "sleet", "slim", "slogan", "sloop",
    "slug", "smoke", "snack", "snapper", "snapshot", "sneaker", "snow",
    "soap", "soccer", "socket", "soda", "sofa", "solar", "soldier", "sombrero",
    "sonnet", "soot", "soprano", "sorbet", "sound", "soup", "soybean",
    "space", "spade", "spaniel", "sparkle", "sparrow", "spatula", "spawn",
    "speech", "sphere", "spice", "spider", "spike", "spinach", "spiral",
    "spire", "splash", "sponge", "spool", "spoon", "spore", "spray",
    "sprig", "spruce", "spur", "spy", "squall", "square", "squash",
    "squid", "stable", "stadium", "staff", "stage", "stair", "stamp",
    "stand", "staple", "starch", "starling", "statue", "steed", "steel",
    "stem", "steppe", "stereo", "stern", "stew", "sticker", "stipule",
    "stirrup", "stock", "stole", "stomp", "stool", "stove", "strap",
    "straw", "stream", "street", "strudel", "stucco", "studio", "study",
    "stump", "stunt", "stylish", "suede", "sugar", "suitcase", "sulfur",
    "summer", "sundial", "sunflower", "surf", "swallow", "swamp", "swan",
    "sweater", "swift", "swimmer", "switch", "sword", "sycamore", "symbol",
    "syntax", "syrup", "table", "tackle", "taco", "tailor", "talent",
    "tandem", "tangent", "tanker", "tapestry", "tapioca", "tarpaulin",
    "tassel", "tavern", "teacher", "tempo", "tenant", "tendon", "tenor",
    "tent", "tepee", "terminal", "terrace", "texture", "thaw", "theater",
    "theme", "thicket", "thimble", "thistle", "thorn", "thread", "threat",
    "throne", "thumb", "thunder", "ticket", "tidal", "tiger", "timber",
    "timpani", "tinder", "tinsel", "tint", "tipple", "tire", "titanium",
    "toffee", "tofu", "toilet", "token", "tomato", "tonic", "tool",
    "topaz", "topple", "torch", "tornado", "torrent", "toucan", "touch",
    "towel", "tower", "trace", "track", "trailer", "train", "tram",
    "tranquil", "travel", "tray", "treble", "trellis", "tremor", "trench",
    "triangle", "tribune", "trident", "trilogy", "trinket", "trio",
    "triple", "trivet", "trolley", "trombone", "trophy", "tropic",
    "trout", "truck", "truffle", "trumpet", "trunk", "trust", "truth",
    "tryst", "tuba", "tucker", "tulip", "tumble", "tundra", "tunic",
    "tunnel", "turbine", "turkey", "turnip", "turtle", "tusk", "tutor",
    "tweezers", "twilight", "twine", "twist", "typist", "ukulele",
    "umber", "umpire", "uncle", "under", "unfold", "unicorn", "unify",
    "union", "unique", "unison", "unity", "unjust", "unrest", "unroll",
    "unseen", "untie", "unwrap", "upbeat", "uphill", "upland", "uplift",
    "upper", "uproar", "upset", "uptown", "upward", "urban", "urchin",
    "urgent", "usable", "useful", "usher", "usual", "utensil", "utility",
    "utmost", "utter", "vacant", "vagrant", "valley", "valor", "value",
    "valve", "vandal", "vane", "vanilla", "vant", "vapor", "variant",
    "vault", "vector", "velvet", "vendor", "venture", "venue", "verbal",
    "verdict", "vermin", "verses", "vessel", "vest", "veteran", "veto",
    "vexing", "viable", "vibrant", "victory", "video", "viewer",
    "vigor", "viking", "village", "vine", "vinyl", "violet", "violin",
    "virtue", "visa", "vision", "visit", "vista", "visual", "vital",
    "vivid", "vixen", "vocal", "vodka", "vogue", "voice", "volume",
    "voter", "voucher", "vowel", "voyage", "waffle", "wagon", "walnut",
    "walrus", "wand", "warder", "warfare", "warmth", "warrior", "washer",
    "wasabi", "watch", "water", "wattle", "wax", "weasel", "weather",
    "weave", "wedding", "wedge", "weed", "weekend", "weevil", "welcome",
    "welfare", "western", "whale", "wheat", "wheel", "whimsy", "whisker",
    "whiskey", "whistle", "white", "wicker", "widget", "wield", "wildcat",
    "willow", "winch", "window", "winner", "winter", "wisdom", "wisent",
    "wisteria", "wizard", "wolf", "wombat", "wonder", "wooden", "wool",
    "word", "work", "world", "worth", "wound", "woven", "wrangle",
    "wreath", "wren", "wrench", "wrinkle", "writer", "yacht", "yam",
    "yard", "yarn", "yawning", "yearling", "yeast", "yellow", "yeoman",
    "yield", "yodel", "yogurt", "yoke", "yolk", "young", "yucca",
    "yummy", "zander", "zeal", "zebra", "zenith", "zephyr", "zeppelin",
    "zest", "zigzag", "zinc", "zinnia", "zircon", "zodiac", "zombie",
    "zoning", "zoo", "zoom",
)

private val aléa = SecureRandom()

/** Tire six mots distincts et les joint par des tirets. */
fun générerJeton(): String {
    val choisis = MOTS.toMutableList()
    val mots = buildList {
        repeat(6) {
            val i = aléa.nextInt(choisis.size)
            add(choisis.removeAt(i))
        }
    }
    return mots.joinToString("-")
}

// — Modèles -----------------------------------------------------------------

@Serializable
data class Devoir(
    val id: Long,
    val auteur: String,          // jeton de mots du parent
    val matière: String,
    val contenu: String,         // description du devoir suggéré
    val dateRemise: String? = null,
    val votes: Int = 0,
    val crééÀ: Long,
)

/** Vue publique d'un devoir : le jeton de l'auteur n'en fait jamais partie,
 *  il permettrait d'identifier (et de deviner) un compte. */
@Serializable
data class DevoirPublic(
    val id: Long,
    val matière: String,
    val contenu: String,
    val dateRemise: String? = null,
    val votes: Int,               // sans défaut : toujours sérialisé, même à 0
    val crééÀ: Long,
)

fun Devoir.public() = DevoirPublic(id, matière, contenu, dateRemise, votes, crééÀ)

@Serializable
data class DevoirEntrée(val matière: String, val contenu: String, val dateRemise: String? = null)

@Serializable
data class Vote(val vote: Int)   // +1 ou -1

@Serializable
data class Problème(
    val id: Long,
    val auteur: String,
    val description: String,     // problème signalé dans l'emploi du temps
    val date: String,            // date concernée (ISO)
    val crééÀ: Long,
)

@Serializable
data class ProblèmeEntrée(val description: String, val date: String)

@Serializable
data class Correction(
    val id: Long,
    val auteur: String,
    val problèmeId: Long? = null, // signalement corrigé, le cas échéant
    val description: String,      // emploi du temps corrigé proposé
    val date: String,
    val crééÀ: Long,
)

@Serializable
data class CorrectionEntrée(val problèmeId: Long? = null, val description: String, val date: String)

@Serializable
data class Compte(val jeton: String)

/** Signalement d'abus : un contenu (devoir, problème ou correction) est
 *  signalé à la modération. */
@Serializable
data class Signalement(
    val id: Long,
    val auteur: String,        // jeton du signalant
    val cible: String,         // « devoir », « probleme » ou « correction »
    val cibleId: Long,
    val raison: String,
    val crééÀ: Long,
)

/** Vue publique d'un signalement : le jeton du signalant n'en fait jamais
 *  partie, au même titre que celui de l'auteur d'un contenu. */
@Serializable
data class SignalementPublic(
    val id: Long,
    val cible: String,
    val cibleId: Long,
    val raison: String,
    val crééÀ: Long,
)

fun Signalement.public() = SignalementPublic(id, cible, cibleId, raison, crééÀ)

@Serializable
data class SignalementEntrée(val cible: String, val cibleId: Long, val raison: String)

/** Cibles qu'un signalement peut viser, sans accent comme les routes. */
val CIBLES = setOf("devoir", "probleme", "correction")

// — Stockage (fichier JSON, sauvegarde après chaque écriture) ---------------

class Stockage(private val fichier: File) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val verrou = ReentrantReadWriteLock()
    private val idSuivant = AtomicLong(1)

    val devoirs = mutableListOf<Devoir>()
    val problèmes = mutableListOf<Problème>()
    val corrections = mutableListOf<Correction>()
    val comptes = mutableSetOf<String>() // jetons connus
    val signalements = mutableListOf<Signalement>()

    /** Votes enregistrés : devoirId → jeton → valeur (+1 ou -1). Un jeton n'a
     *  qu'un vote par devoir ; un second vote remplace le précédent. */
    val votes = mutableMapOf<Long, MutableMap<String, Int>>()

    fun id() = idSuivant.getAndIncrement()

    /** Suppression physique d'un devoir : le fichier JSON est réécrit sans lui,
     *  ses votes et ses signalements sont purgés. Renvoie false si absent. */
    fun supprimerDevoir(id: Long): Boolean = verrou.write {
        val indice = devoirs.indexOfFirst { it.id == id }
        if (indice < 0) false
        else {
            devoirs.removeAt(indice)
            votes.remove(id)
            purgerSignalements("devoir", id)
            true
        }
    }

    /** Suppression physique d'un signalement d'emploi du temps. */
    fun supprimerProblème(id: Long): Boolean = verrou.write {
        val indice = problèmes.indexOfFirst { it.id == id }
        if (indice < 0) false
        else {
            problèmes.removeAt(indice)
            purgerSignalements("probleme", id)
            true
        }
    }

    /** Suppression physique d'une correction d'emploi du temps. */
    fun supprimerCorrection(id: Long): Boolean = verrou.write {
        val indice = corrections.indexOfFirst { it.id == id }
        if (indice < 0) false
        else {
            corrections.removeAt(indice)
            purgerSignalements("correction", id)
            true
        }
    }

    /** Un signalement dont la cible disparaît n'a plus de sens. */
    private fun purgerSignalements(cible: String, cibleId: Long) {
        signalements.removeAll { it.cible == cible && it.cibleId == cibleId }
    }

    /** Révoque un jeton : il ne peut plus rien écrire, mais son contenu reste. */
    fun révoquerJeton(jeton: String): Boolean = verrou.write { comptes.remove(jeton) }

    /** Enregistre un signalement ; renvoie null s'il existe déjà pour ce couple
     *  (auteur, cible). L'identifiant n'est alors pas consommé. */
    fun signaler(auteur: String, cible: String, cibleId: Long, raison: String): Signalement? =
        verrou.write {
            val déjàSignale = signalements.any {
                it.auteur == auteur && it.cible == cible && it.cibleId == cibleId
            }
            if (déjàSignale) null
            else Signalement(id(), auteur, cible, cibleId, raison, now)
                .also { signalements.add(it) }
        }

    /** Enregistre le vote d'un jeton sur un devoir et renvoie le devoir mis à
     *  jour. Le total est ajusté de l'écart avec le vote précédent éventuel. */
    fun voter(devoirId: Long, jeton: String, vote: Int): Devoir = verrou.write {
        val indice = devoirs.indexOfFirst { it.id == devoirId }
        val devoir = devoirs[indice]
        val écart = vote - (votes[devoirId]?.get(jeton) ?: 0)
        val modifié = devoir.copy(votes = devoir.votes + écart)
        devoirs[indice] = modifié
        votes.getOrPut(devoirId) { mutableMapOf() }[jeton] = vote
        modifié
    }

    fun charger() {
        if (!fichier.exists()) return
        verrou.write {
            runCatching {
                val état = json.decodeFromString<ÉtatSauvegardé>(fichier.readText())
                devoirs.clear(); devoirs.addAll(état.devoirs)
                problèmes.clear(); problèmes.addAll(état.problèmes)
                corrections.clear(); corrections.addAll(état.corrections)
                comptes.clear(); comptes.addAll(état.comptes)
                signalements.clear(); signalements.addAll(état.signalements)
                votes.clear()
                état.votes.forEach { (id, parJeton) -> votes[id] = parJeton.toMutableMap() }
                idSuivant.set(état.idSuivant)
            }
        }
    }

    fun sauvegarder() {
        verrou.read {
            val état = ÉtatSauvegardé(
                devoirs = devoirs.toList(),
                problèmes = problèmes.toList(),
                corrections = corrections.toList(),
                comptes = comptes.toList(),
                signalements = signalements.toList(),
                votes = votes.mapValues { (_, parJeton) -> parJeton.toMap() },
                idSuivant = idSuivant.get(),
            )
            fichier.writeText(json.encodeToString(état))
        }
    }

    @Serializable
    data class ÉtatSauvegardé(
        val devoirs: List<Devoir> = emptyList(),
        val problèmes: List<Problème> = emptyList(),
        val corrections: List<Correction> = emptyList(),
        val comptes: List<String> = emptyList(),
        val signalements: List<Signalement> = emptyList(),
        val votes: Map<Long, Map<String, Int>> = emptyMap(),
        val idSuivant: Long = 1,
    )
}

// — API ---------------------------------------------------------------------

private val now: Long get() = System.currentTimeMillis()

// — Limitation de débit ------------------------------------------------------

/** Taille maximale d'un corps de requête : au-delà, réponse 413. Chaque
 *  écriture réécrit le fichier JSON en entier, on ne lit donc jamais un corps
 *  plus gros que nécessaire. */
const val TAILLE_CORPS_MAXIMALE = 10 * 1024

/** Seuils de limitation de débit, surchargeables dans les tests. */
data class Limites(
    val écritureParJeton: Int = 30,      // écritures / minute / jeton
    val écritureParIP: Int = 60,         // écritures / minute / IP
    val inscriptionParMinute: Int = 10,  // comptes / minute / IP
    val inscriptionParJour: Int = 100,   // comptes / 24 h / IP
)

// — CORS ---------------------------------------------------------------------

/** Origines autorisées à appeler le serveur depuis un navigateur, lues dans
 *  « GWS_ORIGINS » : une liste d'origines (`https://host[:port]`) séparées
 *  par des virgules, des points-virgules ou des espaces. Sans variable — ou
 *  avec une valeur vide — aucune origine n'est autorisée : le serveur n'est
 *  pas une API publique, on n'ouvre le cross-origin que sur demande. */
fun originesAutorisées(valeur: String? = System.getenv("GWS_ORIGINS")): List<String> =
    valeur.orEmpty()
        .split(',', ';', ' ', '\n', '\t')
        .map { it.trim().trimEnd('/') }
        .filter { it.isNotEmpty() }
        .distinct()

fun Application.module(
    stockage: Stockage,
    jetonAdmin: String? = System.getenv("GWS_ADMIN_TOKEN"),
    limites: Limites = Limites(),
    origines: Collection<String> = originesAutorisées(),
) {
    install(ContentNegotiation) { json() }

    // Un appel de navigateur depuis une autre origine (application parente,
    // portail élève, page statique hébergée ailleurs) déclenche un pré-vol
    // OPTIONS : sans ce plugin il recevait 404, et sans en-tête
    // Access-Control-Allow-Origin la réponse était rejetée côté client.
    install(CORS) {
        val autorisées = origines.toSet()
        if (autorisées.isNotEmpty()) allowOrigins { it in autorisées }
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Delete)
        // Content-Type aussi : le plugin le signale alors comme non simple,
        // condition pour que « application/json » soit accepté au pré-vol.
        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.ContentType)
        allowCredentials = false   // authentification par jeton, jamais par cookie
    }

    install(RateLimit) {
        // Écritures : un budget par jeton (ou par IP, faute de jeton), puis un
        // plafond global par IP qui les englobe tous — derrière un NAT, toute
        // une école partage la même adresse mais peut aussi être visée d'un
        // seul trait.
        register(RateLimitName("ecriture")) {
            rateLimiter(limit = limites.écritureParJeton, refillPeriod = 1.minutes)
            requestKey { call ->
                jetonDans(call.request.headers["Authorization"]) ?: call.request.origin.remoteHost
            }
        }
        register(RateLimitName("ecriture-ip")) {
            rateLimiter(limit = limites.écritureParIP, refillPeriod = 1.minutes)
            requestKey { call -> call.request.origin.remoteHost }
        }
        // Inscription : POST /compte est la route la plus abusable — un script
        // y crée gratuitement des jetons. Rafale bornée à la minute, puis
        // budget quotidien, tous deux par IP.
        register(RateLimitName("inscription-rafale")) {
            rateLimiter(limit = limites.inscriptionParMinute, refillPeriod = 1.minutes)
            requestKey { call -> call.request.origin.remoteHost }
        }
        register(RateLimitName("inscription-jour")) {
            rateLimiter(limit = limites.inscriptionParJour, refillPeriod = 24.hours)
            requestKey { call -> call.request.origin.remoteHost }
        }
    }

    routing {
        get("/health") { call.respondText("OK") }

        // Les lectures ne réécrivent rien : aucune limitation de débit.
        // Suggestions de devoirs — les réponses publiques ne portent jamais
        // le jeton de l'auteur, qui permettrait d'identifier un compte.
        get("/devoirs") { call.respond(stockage.devoirs.map { it.public() }) }

        // Signalements d'emploi du temps
        get("/edt/problemes") { call.respond(stockage.problèmes) }

        // Corrections proposées pour l'emploi du temps
        get("/edt/corrections") { call.respond(stockage.corrections) }

        // Signalements d'abus : un contenu est signalé à la modération, qui
        // peut ensuite le supprimer. Le jeton du signalant reste secret.
        get("/signalements") { call.respond(stockage.signalements.map { it.public() }) }

        // Inscription : rafale à la minute puis budget quotidien, par IP.
        rateLimit(RateLimitName("inscription-rafale")) {
            rateLimit(RateLimitName("inscription-jour")) {
                // Inscription : le serveur attribue un jeton de mots — aucun nom,
                // aucune donnée personnelle ne transite jamais.
                post("/compte") {
                    val jeton = générerJeton()
                    stockage.comptes.add(jeton)
                    stockage.sauvegarder()
                    call.respond(HttpStatusCode.Created, Compte(jeton))
                }
            }
        }

        // Écritures : budget par jeton, puis plafond par IP qui les englobe.
        rateLimit(RateLimitName("ecriture")) {
            rateLimit(RateLimitName("ecriture-ip")) {
                // Révocation : le jeton cesse d'exister et ne peut plus rien écrire.
                // Le contenu qu'il a publié reste en place, supprimable ensuite par la
                // modération — un jeton révoqué ne peut plus agir, ni même se
                // supprimer lui-même.
                delete("/compte") {
                    val jeton = call.jeton() ?: return@delete call.respond(HttpStatusCode.Unauthorized, "Jeton requis")
                    if (!stockage.révoquerJeton(jeton)) {
                        return@delete call.respond(HttpStatusCode.Unauthorized, "Jeton inconnu ou révoqué")
                    }
                    stockage.sauvegarder()
                    call.respond(HttpStatusCode.NoContent)
                }

                post("/devoirs") {
                    val jeton = call.jetonÉcriture(stockage, jetonAdmin) ?: return@post
                    val entrée = call.recevoirÉcriture<DevoirEntrée>() ?: return@post
                    if (entrée.matière.isBlank() || entrée.contenu.isBlank()) {
                        return@post call.respond(HttpStatusCode.BadRequest, "matière et contenu sont obligatoires")
                    }
                    val devoir = Devoir(
                        id = stockage.id(), auteur = jeton, matière = entrée.matière,
                        contenu = entrée.contenu, dateRemise = entrée.dateRemise, crééÀ = now,
                    )
                    stockage.devoirs.add(devoir)
                    stockage.sauvegarder()
                    call.respond(HttpStatusCode.Created, devoir.public())
                }
                // Suppression : réservée à l'auteur du jeton ou à la modération.
                delete("/devoirs/{id}") {
                    val jeton = call.jetonÉcriture(stockage, jetonAdmin) ?: return@delete
                    val id = call.parameters["id"]?.toLongOrNull()
                        ?: return@delete call.respond(HttpStatusCode.BadRequest, "id invalide")
                    val devoir = stockage.devoirs.find { it.id == id }
                        ?: return@delete call.respond(HttpStatusCode.NotFound, "devoir introuvable")
                    if (!peutSupprimer(devoir.auteur, jeton, jetonAdmin)) {
                        return@delete call.respond(HttpStatusCode.Forbidden, "seul l'auteur ou la modération peut supprimer ce devoir")
                    }
                    stockage.supprimerDevoir(id)
                    stockage.sauvegarder()
                    call.respond(HttpStatusCode.NoContent)
                }
                // Un vote par jeton et par devoir : un second vote remplace le premier.
                post("/devoirs/{id}/vote") {
                    val jeton = call.jetonÉcriture(stockage, jetonAdmin) ?: return@post
                    val id = call.parameters["id"]?.toLongOrNull()
                        ?: return@post call.respond(HttpStatusCode.BadRequest, "id invalide")
                    val vote = (call.recevoirÉcriture<Vote>() ?: return@post).vote
                    if (vote != 1 && vote != -1) {
                        return@post call.respond(HttpStatusCode.BadRequest, "vote doit valoir +1 ou -1")
                    }
                    val devoir = stockage.devoirs.find { it.id == id }
                        ?: return@post call.respond(HttpStatusCode.NotFound, "devoir introuvable")
                    if (devoir.auteur == jeton) {
                        return@post call.respond(HttpStatusCode.Forbidden, "on ne vote pas pour sa propre suggestion")
                    }
                    val modifié = stockage.voter(id, jeton, vote)
                    stockage.sauvegarder()
                    call.respond(modifié.public())
                }

                post("/edt/problemes") {
                    val jeton = call.jetonÉcriture(stockage, jetonAdmin) ?: return@post
                    val entrée = call.recevoirÉcriture<ProblèmeEntrée>() ?: return@post
                    if (entrée.description.isBlank() || entrée.date.isBlank()) {
                        return@post call.respond(HttpStatusCode.BadRequest, "description et date sont obligatoires")
                    }
                    val problème = Problème(stockage.id(), jeton, entrée.description, entrée.date, now)
                    stockage.problèmes.add(problème)
                    stockage.sauvegarder()
                    call.respond(HttpStatusCode.Created, problème)
                }
                delete("/edt/problemes/{id}") {
                    val jeton = call.jetonÉcriture(stockage, jetonAdmin) ?: return@delete
                    val id = call.parameters["id"]?.toLongOrNull()
                        ?: return@delete call.respond(HttpStatusCode.BadRequest, "id invalide")
                    val problème = stockage.problèmes.find { it.id == id }
                        ?: return@delete call.respond(HttpStatusCode.NotFound, "problème introuvable")
                    if (!peutSupprimer(problème.auteur, jeton, jetonAdmin)) {
                        return@delete call.respond(HttpStatusCode.Forbidden, "seul l'auteur ou la modération peut supprimer ce signalement")
                    }
                    stockage.supprimerProblème(id)
                    stockage.sauvegarder()
                    call.respond(HttpStatusCode.NoContent)
                }

                post("/edt/corrections") {
                    val jeton = call.jetonÉcriture(stockage, jetonAdmin) ?: return@post
                    val entrée = call.recevoirÉcriture<CorrectionEntrée>() ?: return@post
                    if (entrée.description.isBlank() || entrée.date.isBlank()) {
                        return@post call.respond(HttpStatusCode.BadRequest, "description et date sont obligatoires")
                    }
                    entrée.problèmeId?.let { id ->
                        if (stockage.problèmes.none { it.id == id }) {
                            return@post call.respond(HttpStatusCode.BadRequest, "problèmeId $id inconnu")
                        }
                    }
                    val correction = Correction(
                        stockage.id(), jeton, entrée.problèmeId, entrée.description, entrée.date, now,
                    )
                    stockage.corrections.add(correction)
                    stockage.sauvegarder()
                    call.respond(HttpStatusCode.Created, correction)
                }
                delete("/edt/corrections/{id}") {
                    val jeton = call.jetonÉcriture(stockage, jetonAdmin) ?: return@delete
                    val id = call.parameters["id"]?.toLongOrNull()
                        ?: return@delete call.respond(HttpStatusCode.BadRequest, "id invalide")
                    val correction = stockage.corrections.find { it.id == id }
                        ?: return@delete call.respond(HttpStatusCode.NotFound, "correction introuvable")
                    if (!peutSupprimer(correction.auteur, jeton, jetonAdmin)) {
                        return@delete call.respond(HttpStatusCode.Forbidden, "seul l'auteur ou la modération peut supprimer cette correction")
                    }
                    stockage.supprimerCorrection(id)
                    stockage.sauvegarder()
                    call.respond(HttpStatusCode.NoContent)
                }

                post("/signalements") {
                    val jeton = call.jetonÉcriture(stockage, jetonAdmin) ?: return@post
                    val entrée = call.recevoirÉcriture<SignalementEntrée>() ?: return@post
                    if (entrée.raison.isBlank()) {
                        return@post call.respond(HttpStatusCode.BadRequest, "raison obligatoire")
                    }
                    if (entrée.cible !in CIBLES) {
                        return@post call.respond(
                            HttpStatusCode.BadRequest, "cible doit valoir devoir, probleme ou correction")
                    }
                    val cibleExiste = when (entrée.cible) {
                        "devoir" -> stockage.devoirs.any { it.id == entrée.cibleId }
                        "probleme" -> stockage.problèmes.any { it.id == entrée.cibleId }
                        else -> stockage.corrections.any { it.id == entrée.cibleId }
                    }
                    if (!cibleExiste) {
                        return@post call.respond(
                            HttpStatusCode.NotFound, "${entrée.cible} ${entrée.cibleId} introuvable")
                    }
                    val signalement = stockage.signaler(jeton, entrée.cible, entrée.cibleId, entrée.raison)
                        ?: return@post call.respond(HttpStatusCode.Conflict, "contenu déjà signalé")
                    stockage.sauvegarder()
                    call.respond(HttpStatusCode.Created, signalement.public())
                }
            }
        }
    }
}

/** Extrait le jeton « Bearer … » d'un en-tête Authorization. */
private fun jetonDans(entête: String?): String? {
    val parties = entête?.split(' ', limit = 2) ?: return null
    return if (parties.size == 2 && parties[0] == "Bearer" && parties[1].isNotBlank()) parties[1].trim() else null
}

/** Extrait le jeton « Bearer … » de l'en-tête Authorization. */
private fun io.ktor.server.routing.RoutingCall.jeton(): String? =
    jetonDans(request.headers["Authorization"])

/** Jeton exigé pour toute écriture : un compte connu du serveur (donc non
 *  révoqué) ou le jeton de modération. Répond 401 et renvoie null sinon. */
private suspend fun io.ktor.server.routing.RoutingCall.jetonÉcriture(
    stockage: Stockage,
    jetonAdmin: String?,
): String? {
    val jeton = jeton() ?: run {
        respond(HttpStatusCode.Unauthorized, "Jeton requis")
        return null
    }
    if (!jeton.estAdmin(jetonAdmin) && jeton !in stockage.comptes) {
        respond(HttpStatusCode.Unauthorized, "Jeton inconnu ou révoqué")
        return null
    }
    return jeton
}

/** Reçoit le corps JSON d'une écriture. Au-delà de [TAILLE_CORPS_MAXIMALE]
 *  octets, répond 413 et renvoie null : l'appelant abandonne alors la route.
 *  Un corps déclaré (Content-Length) est refusé avant lecture ; un corps sans
 *  longueur déclarée (« chunked ») est lu à la laisse, jamais plus. */
private suspend inline fun <reified T : Any> io.ktor.server.routing.RoutingCall.recevoirÉcriture(): T? {
    val déclarée = request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
    if (déclarée != null) {
        if (déclarée > TAILLE_CORPS_MAXIMALE) {
            respond(HttpStatusCode.PayloadTooLarge, "corps limité à 10 Ko")
            return null
        }
        return receive<T>()
    }
    val lu = receiveChannel().readRemaining(TAILLE_CORPS_MAXIMALE + 1L).readByteArray()
    if (lu.size > TAILLE_CORPS_MAXIMALE) {
        respond(HttpStatusCode.PayloadTooLarge, "corps limité à 10 Ko")
        return null
    }
    return try {
        Json.decodeFromString<T>(lu.decodeToString())
    } catch (e: SerializationException) {
        respond(HttpStatusCode.BadRequest, "corps JSON invalide")
        null
    }
}

/** True si le jeton est celui de la modération (« GWS_ADMIN_TOKEN »). */
private fun String.estAdmin(jetonAdmin: String?) = !jetonAdmin.isNullOrBlank() && this == jetonAdmin

/** Un contenu ne peut être supprimé que par son auteur ou par la modération. */
private fun peutSupprimer(auteur: String, jeton: String, jetonAdmin: String?) =
    auteur == jeton || jeton.estAdmin(jetonAdmin)

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    val stockage = Stockage(File(System.getenv("GWS_DATA") ?: "data/communaute.json"))
    stockage.charger()
    embeddedServer(Netty, port = port) { module(stockage) }.start(wait = true)
}
