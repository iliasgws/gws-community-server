# gws-community-server

Serveur communautaire pour **Greenwood School +** — totalement séparé de
l'API Boti de l'école. Il héberge les contributions des parents et élèves :

- **Devoirs suggérés** — un parent propose un devoir (matière, contenu,
  date de remise) ; les autres parents le voient et votent.
- **Signalements d'emploi du temps** — signaler un problème (cours manquant,
  salle erronée…), visible par tous les parents.
- **Corrections d'emploi du temps** — proposer une correction rattachée à un
  signalement.
- **Signalements d'abus** — signaler un contenu (devoir, problème, correction)
  pour qu'un modérateur le retire.

## Authentification : jeton de mots

Aucun compte, aucun mot de passe, aucune donnée personnelle. À la première
utilisation, le client appelle :

```
POST /compte   →   {"jeton":"eagle-smell-bootlace-hypnoses-saddlebag-bunkhouse",
                    "mentionsVersion":"2026-09-27"}
```

`mentionsVersion` est la date de la notice à présenter avant la première
écriture (voir **Mentions** plus bas) : un client qui garde la notice en
local la compare à celle-ci pour savoir si sa copie est périmée.

Le jeton — six mots tirés d'une liste de 243, soit ≈ 2×10¹⁴ combinaisons —
sert à la fois d'identité et d'identifiant d'affichage. Chaque requête
d'écriture le passe en en-tête :

```
Authorization: Bearer eagle-smell-bootlace-hypnoses-saddlebag-bunkhouse
```

Le serveur ne reçoit jamais de nom ; les lectures sont publiques.

Les jetons d'auteur ne figurent jamais dans une réponse : `GET /devoirs`,
`GET /edt/problemes`, `GET /edt/corrections`, `POST /devoirs` et
`POST /devoirs/{id}/vote` renvoient leurs contenus sans champ `auteur`, ce
qui empêche d'identifier (ou de deviner) un compte à partir des listes
publiques. À la place, chaque contenu porte **`auteurId`** : un identifiant
pseudonyme stable, obtenu par empreinte tronquée du jeton, qui permet de
reconnaître les contributions d'un même compte — les signatures « même auteur »
— sans jamais exposer le jeton lui-même. Un même compte a toujours le même
`auteurId`, sur toutes les listes.

Toute écriture n'accepte que deux jetons : un jeton délivré par `POST /compte`
(ou le jeton de modération). Un jeton révoqué ou inconnu reçoit `401`.

## Suppression et modération

La suppression est **physique** : la ligne quitte la base, ses votes et ses
signalements compris.

- `DELETE /devoirs/{id}`, `DELETE /edt/problemes/{id}`,
  `DELETE /edt/corrections/{id}` — réservés à **l'auteur du jeton**, ou au
  jeton de modération (`GWS_ADMIN_TOKEN`) qui supprime n'importe quel contenu.
- `DELETE /compte` — **révoque le jeton** : il ne peut plus rien écrire (ni se
  supprimer de nouveau). Le contenu qu'il a publié reste en place, à la charge
  de la modération s'il est indésirable.
- `POST /signalements` — signale un contenu abusif (`cible` vaut `devoir`,
  `probleme` ou `correction`) ; `GET /signalements` les liste sans le jeton du
  signalant. Supprimer la cible purge ses signalements.

Réponses : `204 No Content` si la suppression a réussi, `404` si la cible
n'existe pas, `403` si vous n'êtes ni l'auteur ni la modération, `401` si le
jeton est absent, inconnu ou révoqué, `409` si le contenu est déjà signalé.

## Votes

- Un vote est enregistré par couple `(devoirId, jeton)` : **un jeton ne
  compte qu'une fois par devoir**.
- Un second vote du même jeton **remplace** le précédent (+1 → −1 → +1),
  il ne se cumule jamais.
- On ne vote pas pour sa propre suggestion : `403 Forbidden`.
- `vote` n'accepte que `+1` ou `−1`, sinon `400 Bad Request`.

## CORS

Un client embarqué dans une autre origine (application parente, portail
élève, page statique hébergée ailleurs) appelle le serveur depuis un
navigateur : le plugin `ktor-server-cors` répond aux pré-vols `OPTIONS` et
pose les en-têtes attendus.

- **Origines** : la liste `GWS_ORIGINS`, séparées par des virgules ou des
  espaces (ex. `https://parent.greenwood.example,https://portail.example`).
  **Sans cette variable, aucune origine n'est admise** : une origine non
  listée reçoit `403 Forbidden`.
- **Méthodes** : `GET`, `POST`, `DELETE` — les trois familles de l'API.
- **En-têtes** : `Authorization` (le jeton) et `Content-Type`
  (`application/json`).
- **Pas de cookies** : `allowCredentials = false`, l'authentification reste
  portée par le jeton `Authorization`.

Un pré-vol abouti répond `200 OK` avec `Access-Control-Allow-Origin`,
`Access-Control-Allow-Methods`, `Access-Control-Allow-Headers` et
`Access-Control-Max-Age` (Ktor ne renvoie jamais `204` sur ce point).

## Tri, filtres et pagination

Les trois listes publiques se lisent avec des paramètres d'URL (facultatifs,
valeurs sans accent acceptées : `matiere` = `matière`, `recent` = `récent`).
Une valeur mal formée répond `400 Bad Request` — jamais une page vide prise
pour un résultat.

| Paramètre | Routes | Effet |
|---|---|---|
| `tri=votes\|récent` | `/devoirs` | **`votes` par défaut** : votes décroissants, puis `crééÀ` décroissants ; `récent` : `crééÀ` décroissant |
| `matière=…` | `/devoirs` | filtre exact sur la matière, casse et accents ignorés |
| `date=AAAA-MM-JJ` | `/edt/problemes`, `/edt/corrections` | filtre exact sur la date concernée |
| `etat=ouvert\|résolu` | `/edt/problemes` | `résolu` = au moins une correction rattachée au signalement (état déduit, jamais stocké) |
| `problèmeId=…` | `/edt/corrections` | ne renvoie que les corrections d'un signalement |
| `depuis=<époque>` | les trois | ne renvoie que les nouveautés ; millisecondes, ou secondes si la valeur est plus petite que 10¹¹ |
| `limite=…&offset=…` | les trois | pagination, `limite` par défaut **50** (maximum 500), `offset` à 0 |

Chaque réponse porte l'en-tête **`X-Total-Count`** : nombre d'éléments après
filtres mais **avant** pagination — de quoi afficher « 12 résultats, page 1
sur 1 » sans rappeler la liste. Les listes sont toujours triées, jamais dans
l'ordre d'insertion brut : les votes ont donc un effet visible sur
l'affichage.

```
GET /devoirs?tri=votes&matière=SVT&limite=20&offset=0
GET /devoirs?depuis=1790000000000        → uniquement les nouveautés
GET /edt/problemes?date=2026-09-28&etat=ouvert
GET /edt/corrections?problèmeId=42
```

## Mentions

`GET /mentions` (publique, sans jeton) renvoie la notice d'information et de
confidentialité, à afficher avant la première écriture :

```json
{
  "version": "2026-09-27",
  "sections": [
    {"id":"editeur","titre":"Éditeur","texte":"…"},
    {"id":"donnees","titre":"Données collectées","texte":"…"},
    {"id":"usage","titre":"Règles d'usage","texte":"…"},
    {"id":"moderation","titre":"Modération et suppression","texte":"…"},
    {"id":"droits","titre":"Vos droits","texte":"…"}
  ]
}
```

- La notice est **servie par le serveur** : tous les clients affichent la
  même version, sans duplication du texte.
- `version` suit le format `AAAA-MM-JJ` et **ne change que si le texte
  change** ; `POST /compte` renvoie la même valeur dans `mentionsVersion`,
  ce qui permet d'invalider une copie locale. Si l'utilisateur refuse, le
  client peut appeler `DELETE /compte` — le jeton est révoqué.
- Aucun nom ni adresse n'est inventé : le contact de la section « Éditeur »
  provient de la variable `GWS_CONTACT`, et la section le mentionne
  uniquement si elle est définie.

## API

| Méthode | Chemin | Auth | Corps |
|---|---|---|---|
| POST | `/compte` | — | — (→ `jeton`, `mentionsVersion`) |
| DELETE | `/compte` | jeton | — (révocation définitive) |
| GET | `/health` | — | — |
| GET | `/mentions` | — | — (notice, cinq sections) |
| GET | `/devoirs` | — | — (`?tri`, `?matière`, `?depuis`, `?limite`, `?offset`, `X-Total-Count`, sans `auteur`) |
| POST | `/devoirs` | jeton | `{"matière","contenu","dateRemise"?}` |
| DELETE | `/devoirs/{id}` | auteur ou modération | — |
| POST | `/devoirs/{id}/vote` | jeton | `{"vote":1 ou -1}` (un vote par jeton, remplace le précédent, pas pour soi-même) |
| GET | `/edt/problemes` | — | — (`?date`, `?etat`, `?depuis`, `?limite`, `?offset`, `X-Total-Count`, sans `auteur`) |
| POST | `/edt/problemes` | jeton | `{"description","date"}` |
| DELETE | `/edt/problemes/{id}` | auteur ou modération | — |
| GET | `/edt/corrections` | — | — (`?problèmeId`, `?date`, `?depuis`, `?limite`, `?offset`, `X-Total-Count`, sans `auteur`) |
| POST | `/edt/corrections` | jeton | `{"problèmeId"?,"description","date"}` |
| DELETE | `/edt/corrections/{id}` | auteur ou modération | — |
| GET | `/signalements` | — | — (sans `auteur`) |
| POST | `/signalements` | jeton | `{"cible","cibleId","raison"}` |

## Stockage : base SQLite

Les données vivent dans une **base SQLite embarquée** (`org.xerial:sqlite-jdbc`)
à côté du serveur : aucun service externe, le fichier unique convient au
déploiement VPS / systemd décrit plus bas.

- **WAL** (`PRAGMA journal_mode=WAL`) : les lectures ne bloquent plus pendant
  une écriture, et une écriture touche la ligne concernée au lieu de réécrire
  tout l'état — c'était le coût appliqué à chaque `POST` auparavant.
- **Écritures transactionnelles** : un vote (ligne *et* total), une suppression
  (contenu, votes *et* signalements), une inscription vont ensemble ou pas du
  tout ; un arrêt au mauvais moment ne corrompt rien.
- **Requêtes SQL** : filtres, tri et pagination des listes publiques sont
  exécutés par SQLite (`LIMIT`/`OFFSET`), l'historique n'a donc pas à tenir en
  mémoire.
- **Migration** : à la première ouverture, un ancien fichier JSON
  (`data/communaute.json`) est importé dans la base, puis déplacé dans
  `data/archives/`. Un fichier illisible est journalisé en erreur (`Migration
  impossible : …`) et **laissé en place** : jamais de perte silencieuse.
- **Tables** : `comptes`, `devoirs`, `votes` (clé composée
  `devoir_id, jeton` — un vote par jeton et par devoir), `problemes`,
  `corrections`, `signalements`.

## Lancer

```bash
./gradlew run          # écoute sur :8080, base data/communaute.db
./gradlew test         # suite de tests
```

Variables d'environnement : `PORT` (défaut 8080), `GWS_DATA`
(chemin du stockage, défaut `data/communaute.db` ; on peut y laisser
l'ancien `data/communaute.json`, qui est alors importé à la première
ouverture puis archivé),
`GWS_ADMIN_TOKEN` (jeton de modération autorisé à supprimer n'importe quel
contenu ; sans cette variable, aucun jeton n'a ce droit),
`GWS_ORIGINS` (origines autorisées à appeler le serveur depuis un navigateur,
voir **CORS** ; sans cette variable, aucune origine externe n'est admise),
`GWS_CONTACT` (contact affiché dans la section « Éditeur » des `GET /mentions`
— recommandé en production, faute de quoi la notice ne donne aucun moyen de
contact).

## Déploiement

`./gradlew installDist` produit `build/install/gws-community-server/` avec
les scripts `bin/gws-community-server` prêts pour un VPS ou une unité
systemd (le seul prérequis est une JVM 21+).
