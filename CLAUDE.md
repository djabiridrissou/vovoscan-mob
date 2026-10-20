# vovoscan — mobile

Applications Android et iOS de vovoscan en Kotlin Multiplatform : logique partagée en Kotlin, interfaces natives
(Jetpack Compose sur Android, SwiftUI sur iOS). L'API (`../backend`) est la source de vérité ; l'appareil garde
une copie locale pour fonctionner hors ligne. Marque : `brand/BRAND.md` (copie du dossier de marque commun) (sombre uniquement).

## Démarrer et vérifier

```bash
./gradlew check :androidApp:assembleDebug
./gradlew -p build-logic test
scripts/fetch-fonts.sh                     # polices de la marque (sinon, police du système)
cd iosApp && xcodegen && open Vovoscan.xcodeproj
```

API locale : `http://10.0.2.2:8000/` (émulateur Android), `http://localhost:8000/` (simulateur iOS).
Autre adresse : `./gradlew :androidApp:assembleDebug -Pvovoscan.apiBaseUrl=https://…/`.

## Architecture

- Une feature = une tranche verticale en quatre modules : `feature/<nom>/{domain,data,presentation,di}`.
  - `domain` : Kotlin commun pur (entités, interface du dépôt, règles). Ni Ktor, ni SQLDelight, ni Koin.
  - `data` : une source distante (`…RemoteDataSource`, Ktor) et une source locale (`…LocalDataSource`, SQLDelight),
    toutes deux derrière une interface, injectées dans `…RepositoryImpl`.
  - `presentation` : ViewModels partagés (état + intentions). Toute la logique d'écran est ici.
  - `di` : module Koin de la feature (sources, dépôt, ViewModels).
- `core/` : `common` (AppResult, AppError, Freshness, Clock), `network` (client Ktor, enveloppes de l'API),
  `database` (pilote SQLDelight), `security` (Keystore / Keychain), `testing` (faux).
- `shared/` assemble tout pour les deux applications et exporte le framework iOS « Shared » (SKIE).
- Règles vérifiées à la configuration Gradle (`build-logic/.../ModuleGraph.kt`) : une feature ne dépend jamais
  d'une autre ; `domain` ne voit que `:core:common` ; `presentation` ne voit que son `domain` ; `data` ne voit pas
  sa `presentation`. Deux features sur un même écran (fiche + suivi) sont assemblées par l'application.
- Aucune logique dans Swift ni dans les Composables : ils affichent l'état et envoient des intentions.

## Hors ligne d'abord

- Les écrans lisent la base locale (`Flow`), jamais le réseau directement. `refresh…` remplace la copie locale par
  la réponse du serveur ; sans réseau, la copie reste affichée avec un bandeau discret (`Freshness.Offline`).
- Écritures (bibliothèque) : appliquées tout de suite en local, mises en file (`outbox`, une opération par titre),
  envoyées par `sync()` avec l'heure de l'action. Le serveur départage (last-write-wins) et rend l'état gagnant ;
  `updatedSince` récupère les changements des autres appareils, suppressions comprises.
- Base par feature ; après un changement de schéma, ajouter une migration `.sqm`. Cible SQLite 3.18 (Android 8) :
  pas d'`ON CONFLICT … DO UPDATE`. Jeton de session dans `SecureStore` seulement ; tout est effacé à la déconnexion.

## Lecteur

- `feature/reader` : chapitre prêt à lire (`GET api/v1/catalog/chapters/{id}`), copie locale du chapitre, sens de
  lecture (`PagedRightToLeft` pour le manga, `Vertical` pour le webtoon), reprise et progression.
- La progression passe par le port `ReadingProgressSink`, branché dans `shared` sur la bibliothèque : le lecteur ne
  connaît pas `library`. Elle n'est gardée que pour un titre suivi.
- Les images sont des URLs signées, identiques pendant une journée : le cache d'images sert les pages déjà vues.
  Le téléchargement de chapitres pour une lecture hors ligne complète reste à faire.

## iOS : pièges connus

- Champs de saisie : `IntentField` garde le texte côté Swift et envoie chaque frappe en intention ; relire l'état
  Kotlin (asynchrone) écraserait la saisie. Valider avec `submitAfterCommittingInput` : iOS ne remet le dernier
  caractère composé qu'à la perte du focus.
- Pas de propriété Kotlin nommée `description` dans un état exporté : sur iOS c'est `toString()` (NSObject).
- Simulateur : construire signé (`CODE_SIGN_IDENTITY="-"`, ce que fait Xcode par défaut), sinon le Keychain
  refuse d'écrire et la session ne tient pas.

## Interface

- Couleurs : `Brand` (Android) et `BrandColors.swift` (iOS), recopiées de `brand/tokens.json`. Rouge = action,
  cyan = travail en cours (synchronisation, voix), jaune = mise en avant. Texte sur rouge : encre, jamais blanc.
- Bouton principal : ombre dure décalée sans flou, s'enfonce de 2 pt à l'appui (150 ms) avec retour haptique.
- Textes en anglais, phrases courtes, ton calme (« Invite already used. »), séparateur ✦.

## Branches et commits

`v1` est la branche principale (pull request vers `v1`). Messages de commit en français, sans pied de page ni
ligne `Co-Authored-By`.
