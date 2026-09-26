# gws-community-server

Serveur communautaire pour **Greenwood School +** — totalement séparé de
l'API Boti de l'école. Il héberge les contributions des parents et élèves :

- **Devoirs suggérés** — un parent propose un devoir (matière, contenu,
  date de remise) ; les autres parents le voient et votent.
- **Signalements d'emploi du temps** — signaler un problème (cours manquant,
  salle erronée…), visible par tous les parents.
- **Corrections d'emploi du temps** — proposer une correction rattachée à un
  signalement.

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

## API

| Méthode | Chemin | Auth | Corps |
|---|---|---|---|
| POST | `/compte` | — | — |
| GET | `/health` | — | — |
| GET | `/devoirs` | — | — |
| POST | `/devoirs` | jeton | `{"matière","contenu","dateRemise"?}` |
| POST | `/devoirs/{id}/vote` | jeton | `{"vote":1 ou -1}` (pas pour soi-même) |
| GET | `/edt/problemes` | — | — |
| POST | `/edt/problemes` | jeton | `{"description","date"}` |
| GET | `/edt/corrections` | — | — |
| POST | `/edt/corrections` | jeton | `{"problèmeId"?,"description","date"}` |

## Lancer

```bash
./gradlew run          # écoute sur :8080, stockage data/communaute.json
./gradlew test         # suite de tests
```

Variables d'environnement : `PORT` (défaut 8080), `GWS_DATA`
(chemin du fichier de stockage, défaut `data/communaute.json`).

## Déploiement

`./gradlew installDist` produit `build/install/gws-community-server/` avec
les scripts `bin/gws-community-server` prêts pour un VPS ou une unité
systemd (le seul prérequis est une JVM 21+).
