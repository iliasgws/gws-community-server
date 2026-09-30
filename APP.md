# Côté application GWS+ — guide d'intégration

Comment l'application **Greenwood School +** consomme ce serveur. Le README
décrit le serveur lui-même (déploiement, stockage, CORS) ; ce document décrit
ce que l'app doit faire, champ par champ et code par code.

## 1. Principes à respecter côté app

- **Le jeton *est* le compte.** Aucun nom, aucun mot de passe. L'app stocke le
  jeton reçu par `POST /compte` et l'envoie dans `Authorization: Bearer …`
  à chaque écriture. Ne le journalisez jamais, ne l'affichez jamais, ne le
  transmettez à aucun autre service.
- **Les lectures sont publiques**, sans jeton : listes, `/health`, `/mentions`.
- **Un champ `auteur` n'existe jamais dans les réponses** : les contenus
  portent `auteurId` (empreinte pseudonyme, 16 caractères hexadécimaux) —
  c'est ce qui permet les signatures « même auteur » sans exposer le jeton.
- **Le jeton de modération (`GWS_ADMIN_TOKEN`) n'appartient pas à l'app** :
  il est réservé à la modération manuelle. L'app n'en connaît pas.

## 2. Démarrage : compte et mentions

### Premier lancement

```
1. GET  /mentions          → afficher la notice (5 sections)
2. POST /compte            → {"jeton":"…", "mentionsVersion":"2026-09-28"}
3. Afficher la notice AVANT la première écriture, si la version locale
   est absente ou différente de mentionsVersion
4. Stocker le jeton de façon durable (keystore / prefs chiffrées)
5. Si l'utilisateur refuse → DELETE /compte (le jeton est révoqué) et
   oublier le jeton
```

### Lancements suivants

- Jeton stocké → plus besoin de `POST /compte`, on l'utilise tel quel.
- `GET /mentions` compare `version` à la copie locale : même valeur → rien à
  faire ; valeur différente → réafficher la notice avant la prochaine écriture.
- `401` à la première écriture → jeton révoqué ou inconnu : appeler
  `POST /compte` pour en obtenir un nouveau, et afficher la notice.

`GET /mentions` répond :

```json
{
  "version": "2026-09-28",
  "sections": [
    {"id":"editeur","titre":"Éditeur","texte":"…"},
    {"id":"donnees","titre":"Données collectées","texte":"…"},
    {"id":"usage","titre":"Règles d'usage","texte":"…"},
    {"id":"moderation","titre":"Modération et suppression","texte":"…"},
    {"id":"droits","titre":"Vos droits","texte":"…"}
  ]
}
```

`id` est stable : utilisez-le comme clé de traduction ou ancre. La version
suit `AAAA-MM-JJ` et ne change que si le texte change.

## 3. Lecture des listes

Trois listes publiques, même mécanique : paramètres d'URL facultatifs,
`X-Total-Count` en réponse, pagination par `limite`/`offset`.

| Route | Réponse |
|---|---|
| `GET /devoirs` | `[DevoirPublic]` |
| `GET /edt/problemes` | `[ProblèmePublic]` |
| `GET /edt/corrections` | `[CorrectionPublic]` |
| `GET /signalements` | `[SignalementPublic]` (sans pagination) |

Chaque `DevoirPublic` peut aussi contenir `piecesJointes`, une liste de
métadonnées de fichiers (voir le protocole ci-dessous). L'absence de cette
clé équivaut à une liste vide pour les anciennes réponses.

Paramètres (les accents des noms sont facultatifs : `matière` = `matiere`,
`problèmeId` = `problemeId`, `état` = `etat`) :

| Paramètre | Routes | Accepté |
|---|---|---|
| `tri` | `/devoirs` | `votes` (défaut) ou `récent` — casse et accents ignorés |
| `matière` | `/devoirs` | filtre exact, casse et accents ignorés |
| `date` | `/edt/problemes`, `/edt/corrections` | `AAAA-MM-JJ` exact |
| `etat` | `/edt/problemes` | `ouvert` ou `résolu` |
| `problèmeId` | `/edt/corrections` | entier ≥ 1 |
| `depuis` | les trois | époque : millisecondes, ou secondes si < 10¹¹ |
| `limite` | les trois | 1 à 500, défaut **50** |
| `offset` | les trois | ≥ 0, défaut 0 |

Une valeur mal formée → `400 Bad Request` (message explicite), **jamais** une
page vide silencieuse. Une valeur valide mais sans résultat → `[]` avec
`X-Total-Count: 0`.

`X-Total-Count` = éléments **après** filtres, **avant** pagination : c'est le
compteur à afficher (« 12 résultats »), pas `liste.length`.

### Formes de réponse

```jsonc
// DevoirPublic
{"id":42,"auteurId":"3f2a…","matière":"SVT","contenu":"Lire le chapitre 3",
 "dateRemise":"2026-10-02","votes":3,"crééÀ":1790000000000}

// ProblèmePublic — « état » est déduit (une correction rattachée = résolu),
// jamais stocké côté serveur
{"id":7,"auteurId":"3f2a…","description":"Cours de maths manquant",
 "date":"2026-09-28","état":"ouvert","crééÀ":1790000000000}

// CorrectionPublic
{"id":11,"auteurId":"9b1c…","problèmeId":7,"description":"Salle B12 au lieu de A3",
 "date":"2026-09-28","crééÀ":1790000000000}

// SignalementPublic
{"id":3,"cible":"devoir","cibleId":42,"raison":"contenu inapproprié",
 "crééÀ":1790000000000}
```

`dateRemise` (devoirs) et `problèmeId` (corrections) sont **facultatifs** : le
serveur peut omettre la clé lorsqu'elle vaut nulle — parsez-les comme
optionnels. Les champs sans défaut (`id`, `votes`, `état`, `crééÀ`, …)
figurent toujours.

## 4. Écritures

Toutes les écritures JSON exigent `Authorization: Bearer <jeton>` et
`Content-Type: application/json`. Leur corps reste limité à **10 Ko**. Les
fichiers de devoir passent par la route multipart décrite ci-dessous, avec
une limite dédiée de **5 Mo par fichier**.

| Requête | Corps | Succès |
|---|---|---|
| `POST /devoirs` | `{"matière","contenu","dateRemise"?}` | `201` + `DevoirPublic` |
| `POST /devoirs/{id}/pieces-jointes` | multipart `file` | `201` + métadonnées de la pièce jointe |
| `GET /devoirs/{id}/pieces-jointes/{pieceId}` | — | `200` + fichier (téléchargement public) |
| `POST /devoirs/{id}/vote` | `{"vote": 1}` ou `{"vote": -1}` | `200` + `DevoirPublic` (total mis à jour) |
| `POST /edt/problemes` | `{"description","date"}` | `201` + `ProblèmePublic` |
| `POST /edt/corrections` | `{"problèmeId"?,"description","date"}` | `201` + `CorrectionPublic` |
| `POST /signalements` | `{"cible","cibleId","raison"}` | `201` + `SignalementPublic` |
| `DELETE /devoirs/{id}` | — | `204` |
| `DELETE /edt/problemes/{id}` | — | `204` |
| `DELETE /edt/corrections/{id}` | — | `204` |
| `DELETE /compte` | — | `204` (révocation définitive) |

### Fichiers joints à un devoir

Après la création du devoir, son auteur envoie chaque fichier séparément :

```http
POST /devoirs/42/pieces-jointes
Authorization: Bearer <jeton auteur>
Content-Type: multipart/form-data; boundary=...

file=<octets du fichier>
```

Le champ multipart s'appelle `file`. Un fichier peut peser jusqu'à 5 MiB
(5 242 880 octets) ; un dépassement répond `413`. Seul l'auteur du devoir ou
la modération peut ajouter un fichier. La réponse `201` contient par exemple :

```json
{"id":"…","nom":"fiche.pdf","type":"application/pdf","taille":12345,
 "url":"/devoirs/42/pieces-jointes/…"}
```

`GET /devoirs` inclut `piecesJointes`, une liste de ces métadonnées avec des
URLs relatives stables ; les lectures et téléchargements sont publics. Les
propositions antérieures à cette fonctionnalité, ou sans fichier, restent
valides : `piecesJointes` peut être absent dans une ancienne réponse et doit
être traité comme une liste vide. Supprimer un devoir supprime aussi ses
fichiers. Le nom fourni par le client sert uniquement à l'affichage ; le
serveur attribue lui-même le nom de stockage.

Règles métier que l'app doit refléter dans son UI :

- **Vote** : `vote` vaut strictement `1` ou `-1`. Un second vote du même
  jeton **remplace** le premier (pas de cumul) — l'UI doit donc afficher l'état
  du vote, pas un compteur incrémental local. On ne vote pas pour sa propre
  suggestion → `403`.
- **Suppression** : réservée à l'auteur du contenu (jeton correspondant à
  `auteurId`) ou à la modération → sinon `403`. Suppression physique : le
  contenu, ses votes et ses signalements disparaissent (pas d'annulation
  possible).
- **`cible`** d'un signalement : exactement `devoir`, `probleme` ou
  `correction` (sans accent). Un contenu déjà signalé → `409` (l'app peut
  simplement masquer le bouton après signalement).
- **`problèmeId`** d'une correction doit exister, sinon `400`.

## 5. Codes de réponse

| Code | Signification côté app |
|---|---|
| `200` / `201` / `204` | succès — corps absent sur `204` |
| `400` | corps JSON invalide, champ obligatoire vide, paramètre mal formé, `vote` hors `±1`, `problèmeId` inconnu, `id` non entier |
| `401` | jeton absent, inconnu ou révoqué → relancer `POST /compte` |
| `403` | jeton valide mais interdit : suppression par un non-auteur, vote pour soi-même, ou **origine CORS non autorisée** (app web) |
| `404` | ressource introuvable (id supprimé entre-temps) |
| `409` | contenu déjà signalé |
| `413` | corps JSON > 10 Ko ou fichier joint > 5 MiB |
| `429` | limite de débit atteinte, en-tête `Retry-After` = délai d'attente (secondes) |

Les messages d'erreur sont en texte brut, pas en JSON — affichez le message
tel quel ou une libellé générique selon le code.

## 6. Limitation de débit (à gérer dans l'app)

| Limite | Valeur | Portée |
|---|---|---|
| Écritures | 30 / minute | par jeton |
| Écritures | 60 / minute | par IP (englobe le budget ci-dessus) |
| `POST /compte` | 10 / minute | par IP |
| `POST /compte` | 100 / 24 h | par IP |
| **Lectures** | **illimité** | aucun `GET` n'est limité |

À l'app : ne pas enchaîner les écritures (bouton désactivé pendant l'envoi),
respecter `Retry-After` sur `429`, et ne jamais appeler `POST /compte` en
boucle — un seul compte par installation suffit.

## 7. Synchronisation

- **Incrémental** : mémoriser le plus grand `crééÀ` vu, relancer les listes
  avec `?depuis=<valeur>` — seul le champ `crééÀ` (création) est filtré.
- **Limite de l'incrémental** : un vote modifie `votes`, une suppression
  retire une ligne, un `état` peut passer à `résolu` — **aucun de ces
  changements ne touche `crééÀ`**, donc `depuis` ne les rattrape pas.
  Prévoir un rafraîchissement complet périodique (première page, `limite=50`)
  ou à chaque ouverture de l'écran.
- **Pagination** : `X-Total-Count` + `limite`/`offset` permettent une vraie
  pagination ; au-delà de 500 par page, le serveur répond `400`.

## 8. App web (CORS)

Si l'app est appelée depuis un navigateur sur une autre origine que celle du
serveur, deux conditions côté serveur (variables d'environnement, déjà
posées à l'installation) :

- l'origine de l'app doit figurer dans `GWS_ORIGINS` — sinon chaque requête
  reçoit `403` ;
- seuls `GET`, `POST`, `DELETE` et les en-têtes `Authorization` /
  `Content-Type` sont autorisés ; `X-Total-Count` est exposé au JS.

Une app native (Kotlin/Swift) n'est pas concernée : aucune requête
cross-origin.

## 9. Sanity check

```bash
curl -s localhost:8080/health                                  # OK
curl -s localhost:8080/mentions | head -c 120                  # {"version":…
JETON=$(curl -s -X POST localhost:8080/compte | jq -r .jeton)
curl -s -X POST localhost:8080/devoirs \
  -H "Authorization: Bearer $JETON" -H 'Content-Type: application/json' \
  -d '{"matière":"SVT","contenu":"Lire le chapitre 3"}'
curl -si localhost:8080/devoirs | grep -i x-total-count
```
