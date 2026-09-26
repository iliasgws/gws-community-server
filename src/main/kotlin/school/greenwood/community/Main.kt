package school.greenwood.community

import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

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

// — Stockage (fichier JSON, sauvegarde après chaque écriture) ---------------

class Stockage(private val fichier: File) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val verrou = ReentrantReadWriteLock()
    private val idSuivant = AtomicLong(1)

    val devoirs = mutableListOf<Devoir>()
    val problèmes = mutableListOf<Problème>()
    val corrections = mutableListOf<Correction>()
    val comptes = mutableSetOf<String>() // jetons connus

    fun id() = idSuivant.getAndIncrement()

    fun charger() {
        if (!fichier.exists()) return
        verrou.write {
            runCatching {
                val état = json.decodeFromString<ÉtatSauvegardé>(fichier.readText())
                devoirs.clear(); devoirs.addAll(état.devoirs)
                problèmes.clear(); problèmes.addAll(état.problèmes)
                corrections.clear(); corrections.addAll(état.corrections)
                comptes.clear(); comptes.addAll(état.comptes)
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
        val idSuivant: Long = 1,
    )
}

// — API ---------------------------------------------------------------------

private val now: Long get() = System.currentTimeMillis()

fun Application.module(stockage: Stockage) {
    install(ContentNegotiation) { json() }

    routing {
        get("/health") { call.respondText("OK") }

        // Inscription : le serveur attribue un jeton de mots — aucun nom,
        // aucune donnée personnelle ne transite jamais.
        post("/compte") {
            val jeton = générerJeton()
            stockage.comptes.add(jeton)
            stockage.sauvegarder()
            call.respond(HttpStatusCode.Created, Compte(jeton))
        }

        // Suggestions de devoirs
        get("/devoirs") { call.respond(stockage.devoirs) }
        post("/devoirs") {
            val jeton = call.jeton() ?: return@post call.respond(
                HttpStatusCode.Unauthorized, "Fournis le jeton : « Authorization: Bearer <jeton> »")
            val entrée = call.receive<DevoirEntrée>()
            if (entrée.matière.isBlank() || entrée.contenu.isBlank()) {
                return@post call.respond(HttpStatusCode.BadRequest, "matière et contenu sont obligatoires")
            }
            val devoir = Devoir(
                id = stockage.id(), auteur = jeton, matière = entrée.matière,
                contenu = entrée.contenu, dateRemise = entrée.dateRemise, crééÀ = now,
            )
            stockage.devoirs.add(devoir)
            stockage.sauvegarder()
            call.respond(HttpStatusCode.Created, devoir)
        }
        post("/devoirs/{id}/vote") {
            val jeton = call.jeton() ?: return@post call.respond(HttpStatusCode.Unauthorized, "Jeton requis")
            val id = call.parameters["id"]?.toLongOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest, "id invalide")
            val vote = call.receive<Vote>().vote
            if (vote != 1 && vote != -1) {
                return@post call.respond(HttpStatusCode.BadRequest, "vote doit valoir +1 ou -1")
            }
            val devoir = stockage.devoirs.find { it.id == id }
                ?: return@post call.respond(HttpStatusCode.NotFound, "devoir introuvable")
            if (devoir.auteur == jeton) {
                return@post call.respond(HttpStatusCode.Forbidden, "on ne vote pas pour sa propre suggestion")
            }
            val modifié = devoir.copy(votes = devoir.votes + vote)
            stockage.devoirs[stockage.devoirs.indexOf(devoir)] = modifié
            stockage.sauvegarder()
            call.respond(modifié)
        }

        // Signalements d'emploi du temps
        get("/edt/problemes") { call.respond(stockage.problèmes) }
        post("/edt/problemes") {
            val jeton = call.jeton() ?: return@post call.respond(HttpStatusCode.Unauthorized, "Jeton requis")
            val entrée = call.receive<ProblèmeEntrée>()
            if (entrée.description.isBlank() || entrée.date.isBlank()) {
                return@post call.respond(HttpStatusCode.BadRequest, "description et date sont obligatoires")
            }
            val problème = Problème(stockage.id(), jeton, entrée.description, entrée.date, now)
            stockage.problèmes.add(problème)
            stockage.sauvegarder()
            call.respond(HttpStatusCode.Created, problème)
        }

        // Corrections proposées pour l'emploi du temps
        get("/edt/corrections") { call.respond(stockage.corrections) }
        post("/edt/corrections") {
            val jeton = call.jeton() ?: return@post call.respond(HttpStatusCode.Unauthorized, "Jeton requis")
            val entrée = call.receive<CorrectionEntrée>()
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
    }
}

/** Extrait le jeton « Bearer … » de l'en-tête Authorization. */
private fun io.ktor.server.routing.RoutingCall.jeton(): String? {
    val entête = request.headers["Authorization"] ?: return null
    val parties = entête.split(' ', limit = 2)
    return if (parties.size == 2 && parties[0] == "Bearer" && parties[1].isNotBlank()) parties[1].trim() else null
}

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    val stockage = Stockage(File(System.getenv("GWS_DATA") ?: "data/communaute.json"))
    stockage.charger()
    embeddedServer(Netty, port = port) { module(stockage) }.start(wait = true)
}
