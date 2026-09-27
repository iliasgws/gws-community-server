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
POST /compte   →   {"jeton":"eagle-smell-bootlace-hypnoses-saddlebag-bunkhouse"}
```

Le jeton — six mots tirés d'une liste de 243, soit ≈ 2×10¹⁴ combinaisons —
sert à la fois d'identité et d'identifiant d'affichage. Chaque requête
d'écriture le passe en en-tête :

```
Authorization: Bearer eagle-smell-bootlace-hypnoses-saddlebag-bunkhouse
```

Le serveur ne reçoit jamais de nom ; les lectures sont publiques.

Les jetons d'auteur ne figurent jamais dans une réponse : `GET /devoirs`,
`POST /devoirs` et `POST /devoirs/{id}/vote` renvoient un devoir sans champ
`auteur`, ce qui empêche d'identifier (ou de deviner) un compte à partir des
listes publiques.

Toute écriture n'accepte que deux jetons : un jeton délivré par `POST /compte`
(ou le jeton de modération). Un jeton révoqué ou inconnu reçoit `401`.

## Suppression et modération

La suppression est **physique** : le fichier JSON est réécrit sans l'élément,
ses votes et ses signalements compris.

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

## API

| Méthode | Chemin | Auth | Corps |
|---|---|---|---|
| POST | `/compte` | — | — |
| DELETE | `/compte` | jeton | — (révocation définitive) |
| GET | `/health` | — | — |
| GET | `/devoirs` | — | — (sans `auteur`) |
| POST | `/devoirs` | jeton | `{"matière","contenu","dateRemise"?}` |
| DELETE | `/devoirs/{id}` | auteur ou modération | — |
| POST | `/devoirs/{id}/vote` | jeton | `{"vote":1 ou -1}` (un vote par jeton, remplace le précédent, pas pour soi-même) |
| GET | `/edt/problemes` | — | — |
| POST | `/edt/problemes` | jeton | `{"description","date"}` |
| DELETE | `/edt/problemes/{id}` | auteur ou modération | — |
| GET | `/edt/corrections` | — | — |
| POST | `/edt/corrections` | jeton | `{"problèmeId"?,"description","date"}` |
| DELETE | `/edt/corrections/{id}` | auteur ou modération | — |
| GET | `/signalements` | — | — (sans `auteur`) |
| POST | `/signalements` | jeton | `{"cible","cibleId","raison"}` |

## Lancer

```bash
./gradlew run          # écoute sur :8080, stockage data/communaute.json
./gradlew test         # suite de tests
```

Variables d'environnement : `PORT` (défaut 8080), `GWS_DATA`
(chemin du fichier de stockage, défaut `data/communaute.json`),
`GWS_ADMIN_TOKEN` (jeton de modération autorisé à supprimer n'importe quel
contenu ; sans cette variable, aucun jeton n'a ce droit),
`GWS_ORIGINS` (origines autorisées à appeler le serveur depuis un navigateur,
voir **CORS** ; sans cette variable, aucune origine externe n'est admise).

## Déploiement

`./gradlew installDist` produit `build/install/gws-community-server/` avec
les scripts `bin/gws-community-server` prêts pour un VPS ou une unité
systemd (le seul prérequis est une JVM 21+).
