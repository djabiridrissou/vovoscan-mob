# vovoscan — mobile

vovoscan's Android and iOS apps in Kotlin Multiplatform: shared logic in Kotlin, native UIs
(Jetpack Compose on Android, SwiftUI on iOS). The API (`../backend`) is the source of truth; the device keeps
a local copy so it works offline. Brand: `brand/BRAND.md` (copy of the shared brand folder) (dark only).

## Run and verify

```bash
./gradlew check :androidApp:assembleDebug
./gradlew -p build-logic test
scripts/fetch-fonts.sh                     # brand fonts (otherwise, the system font)
cd iosApp && xcodegen && open Vovoscan.xcodeproj
```

Local API: `http://10.0.2.2:8000/` (Android emulator), `http://localhost:8000/` (iOS simulator).
Another address: `./gradlew :androidApp:assembleDebug -Pvovoscan.apiBaseUrl=https://…/`.

## Architecture

- One feature = one vertical slice in four modules: `feature/<name>/{domain,data,presentation,di}`.
  - `domain`: pure common Kotlin (entities, repository interface, rules). No Ktor, no SQLDelight, no Koin.
  - `data`: a remote source (`…RemoteDataSource`, Ktor) and a local source (`…LocalDataSource`, SQLDelight),
    both behind an interface, injected into `…RepositoryImpl`.
  - `presentation`: shared ViewModels (state + intents). All screen logic lives here.
  - `di`: the feature's Koin module (sources, repository, ViewModels).
- `core/`: `common` (AppResult, AppError, Freshness, Clock), `network` (Ktor client, API envelopes),
  `database` (SQLDelight driver), `security` (Keystore / Keychain), `sources` (engine for sources read on the
  device), `testing` (fakes).
- `shared/` wires everything for both apps and exports the iOS "Shared" framework (SKIE).
- Rules checked at Gradle configuration time (`build-logic/.../ModuleGraph.kt`): a feature never depends on
  another; `domain` sees only `:core:common`; `presentation` sees only its `domain`; `data` does not see its
  `presentation`. Two features on the same screen (title page + follow) are wired together by the app.
- No logic in Swift or in Composables: they render the state and send intents.

## Offline first

- Screens read the local database (`Flow`), never the network directly. `refresh…` replaces the local copy with
  the server's response; without network, the copy stays on screen with a discreet banner (`Freshness.Offline`).
- Writes (library): applied locally right away, queued (`outbox`, one operation per title), sent by `sync()` with
  the time of the action. The server settles conflicts (last-write-wins) and returns the winning state;
  `updatedSince` fetches changes from other devices, deletions included.
- One database per feature; after a schema change, add a `.sqm` migration. Target SQLite 3.18 (Android 8):
  no `ON CONFLICT … DO UPDATE`. Session token in `SecureStore` only; everything is wiped on sign-out.

## Sources

- `feature/sources`: the user's active sources (`GET api/v1/catalog/sources`), visible or not, in their order;
  choices applied locally then sent (`PUT`, replayed if they could not go out). At least one stays visible.
  "Sources" screen and catalog picker (`SourcePicker`), wired together by the app.
- Two engines, chosen by `sourceKey` in the remote data sources (`Routing…RemoteDataSource`):
  - `vovoscan` (internal): read through our API.
  - rule-based source (e.g. Project Gutenberg): **the device interprets the manifest and makes the requests
    itself**. Nothing goes through our API, except the `(sourceKey, titleRef)` reference of a followed title and
    its progress.
- `core/sources`: manifest (v1 contract, copy of `vovo-plugin/schema`), JSONPath, `{{settings.x}}` templates,
  CSS selectors (Ksoup), transforms, `RulesSource` (catalog, title page, pages, book URL).
- `RulesHttp`: a separate client, **without the vovoscan session or cookies**, redirects followed by hand and
  limited to declared hosts (manifest + URL-type settings), rate capped by the manifest.
- Catalog, title pages and chapters are keyed by `(sourceKey, id)`: two sources never mix.
  The catalog follows the first visible source until the reader picks another one (`SourcesChanged`).
- Tests: `core/sources/src/commonTest/.../Fixtures.kt` copies the manifests (change them with `vovo-plugin`).

## Reader

- `feature/reader`: a chapter ready to read (`GET api/v1/catalog/chapters/{id}`), local copy of the chapter,
  reading mode (`PagedRightToLeft` for manga, `Vertical` for webtoon, `Book` for a book), resume and progress.
- Books (EPUB): downloaded once (signed URL from the API, or the source's host for a rule-based source),
  read by `EpubParser` (Okio + Ksoup: `container.xml` → OPF → reading order, nav/NCX table of contents), sections
  sanitized (allowlist, images as `data:` only, links removed) and stored in the database: offline rereading.
  Displayed in a WebView / WKWebView with no JavaScript and no network, styles and CSP from `BookStyle`. A book's
  progress counts in sections.
- Progress goes through the `ReadingProgressSink` port, wired to the library in `shared`: the reader does not
  know about `library`. It is kept only for a followed title.
- Images are signed URLs, unchanged for a day: the image cache serves pages already seen.
  Downloading chapters for full offline reading is still to do.

## iOS: known pitfalls

- Text fields: `IntentField` keeps the text on the Swift side and sends each keystroke as an intent; reading back
  the Kotlin state (asynchronous) would overwrite the input. Submit with `submitAfterCommittingInput`: iOS only
  hands over the last composed character when focus is lost.
- Sign-in: when submitting, resend the exact field values (intents) before `Submit`, otherwise the last
  keystroke may be missing on the Kotlin side.
- No Kotlin property named `description` in an exported state: on iOS it is `toString()` (NSObject).
- Simulator: build signed (`CODE_SIGN_IDENTITY="-"`, which Xcode does by default), otherwise the Keychain
  refuses to write and the session does not persist.

## UI

- Colors: `Brand` (Android) and `BrandColors.swift` (iOS), copied from `brand/tokens.json`. Red = action,
  cyan = work in progress (sync, voice), yellow = highlight. Text on red: ink, never white.
- Primary button: hard offset shadow with no blur, presses down 2 pt on tap (150 ms) with haptic feedback.
- Copy in English, short sentences, calm tone ("Invite already used."), ✦ separator.

## Branches and commits

`v1` is the main branch (pull requests target `v1`). Commit messages in English, with no footer and no
`Co-Authored-By` line.
