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
On the Android 17 (API 37) emulator the app's requests to `10.0.2.2` never reached the local API (the emulator itself
could); `adb reverse tcp:8000 tcp:8000` with `-Pvovoscan.apiBaseUrl=http://localhost:8000/` works.

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
- Third-party sources (user plugins, spec v1): added from a link (single manifest or `repo.json`, GitHub pages
  resolved to raw files) or a `.vovo` file, private to the user, not reviewed by vovoscan. `core/sources` has the
  pure parts (`PluginLinks`, `PluginFetcher`: https only, no session, ≤ 3 redirects, byte limits; `RepositoryIndex`,
  `ManifestCheck`, `Semver`, `VovoPackageReader`, `sha256Hex`). `feature/sources` stores them in the same `source`
  table (so `ManifestProvider` serves them), queues writes in `plugin_outbox` (`PUT/DELETE api/v1/plugins`,
  `api/v1/plugin-repositories`), keeps verified updates in `plugin_extra`. Removal goes through the
  `SourceRemovedListener` port, wired in `shared` to the catalog and reader caches.

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

## Account (keys, reading model, costs)

- `feature/account`: the user's own AI settings, one slice for the backend modules `provider-keys` and `settings`
  (one settings area, the same "always the caller's own records" rule). Every signed-in user, Reader included.
- Provider keys (`GET`, `POST`, `DELETE api/v1/provider-keys`): Anthropic and ElevenLabs, one active key each. The
  secret is typed once and sent once, in the POST body (`AddProviderKeyBody`, whose `toString` hides it); it is never
  stored (the `provider_key` table has no column for it), logged, queued or kept in ViewModel state:
  `ProviderKeysViewModel` keeps it in a private draft emptied the moment it is sent, whatever the answer, and bumps
  `secretFieldResets` so the UI empties its field. Only the hint (last 4 characters) is shown. Errors `KEY_REJECTED`,
  `PROVIDER_BLOCKED`, `PROVIDER_UNAVAILABLE`, `BYOK_DISABLED`, 429 have calm messages (`AccountText`). Removal asks
  first and is idempotent (`404 PROVIDER_KEY_NOT_FOUND` counts as removed). `enabled: false`: "Keys are turned off on
  this server."
- Reading model (`GET`, `PUT api/v1/settings/reading`): Haiku (default), Sonnet, Opus. The key list and the model are
  kept on the device; every write needs the network ("You're offline…", never queued).
- Costs (`GET api/v1/provider-keys/usage?from=`): online only; periods in the device's time zone (`DeviceTimeZone` in
  `core/common`): last 7 days, last 30 days, this month.
- ViewModels: `ProviderKeysViewModel`, `ReadingSettingsViewModel`, `UsageViewModel`.

## Episodes

- `feature/episodes`: a comic chapter of our catalog made into a voiced episode with the user's own keys, shared with
  everyone who can see the chapter (`api/v1/episodes/chapters/{chapterId}`: `GET`, `POST` prepare, `POST …/reread`,
  `GET …/events`, `GET …/watch`, `GET`/`POST …/export`). Nobody pays twice: a usable render answers
  `200` and nothing is queued. `canReread` from the API is the truth, never computed on the device.
- Offline first: every answer and live snapshot is saved (`episode` table, last known episode per chapter; an older
  `updatedAt` never replaces a newer one); the last watch (timeline + signed URLs) is kept and reused while its URLs
  last more than 10 minutes, else refetched (and offline, played as it is).
- Live progress: `follow()` reads `…/events` (`text/event-stream`, `event: snapshot` = the episode, `:` comment
  heartbeats, `SseParser`) until the episode is ready or failed. No request timeout on the stream, 45 s without a byte
  is a dead link. Reconnects at once when the server ends the stream (30-minute cap), with backoff (1, 2, 4… 30 s) when
  it fails; after 3 failures (or `429 TOO_MANY_STREAMS`) polls `GET` every 10 s, trying the stream again every minute.
  The screen stops following when it stops (`EpisodeIntent.Stop`).
- `EpisodeViewModel(chapterId)`: no episode yet (Prepare, with the key notice), running (ANALYZE · OCR · VOICE ·
  TIMELINE chips, step x/y), ready (Watch, Degraded / Silent / Outdated badges, "Prepare again" when outdated, reread
  when `canReread`), failed (the server's error, "Try again"). `409 EPISODE_BUSY` follows the run in progress;
  `422 EPISODE_NOT_SUPPORTED` (a book) hides the feature (`visible` false).
- MP4 export (`…/export`, 10 requests an hour): `EpisodeExport` rides on the episode (`EpisodeDto.export`, in `GET` and
  every live snapshot; an export does not move `updatedAt`, so equal timestamps still replace the stored copy). `follow()`
  keeps going while the MP4 is queued or rendering (`Episode.inProgress`). `EpisodeViewModel` shows a Video section once
  the episode is ready ("Make a video" / "Making the video ✦ 42%" / "Save video" / "Try again"); `SaveVideo` fetches a
  fresh signed link (`GET …/export`) and hands it once as `videoDownload` (file name from the signed `name=`); the
  screen answers `VideoHandled` or `VideoFailed`. 409 and 429 have calm messages (`EpisodeText.exportError`).
- Which keys a run would use comes through the `KeyStatusProvider` port (hints only), wired in `shared` to the account
  slice: episodes do not know about `account`.

## Player core

- `feature/episodes/domain/player`: the old web player's logic, ported line for line, never its rendering:
  `Camera.kt` (camera.ts: panel-first framing, push-in, drift, long-panel pans, glides, dissolves, impact shakes,
  caption legibility and placement), `Mix.kt` (mix.ts: bed and score crossfades, ducking, constant-power pan, sweeps,
  repeat cap), `Captions.kt` (captions.ts: caption cards; JavaScript regexes written out as character tests so the JVM
  and Kotlin/Native agree). Pure, deterministic.
- Shared vectors: `feature/episodes/domain/src/commonTest/fixtures/{framing,mix,captions}.json` are copies of the
  backend's `tests/fixtures/episodes/` (= the old `packages/shared/fixtures`): they keep every player and the MP4 export
  identical. Copy them again when the export changes. The `generateVectors` task turns them into Kotlin strings, so the
  same tests run on the JVM and on iOS.
- `PlaybackPlan(watch)`: total duration, beats and pages, and `frame(t, stageW, stageH)` → `PlayerFrame` (page shot:
  framing, stage transforms, panel outline, backdrop; the fading previous shot; the opening veil; caption cards; the
  audio cues playing now with gain and pan). `captionPlacement(…)` places the caption block the native side measured.
- `PlayerViewModel(chapterId, nextChapterId?)`: play, pause, seek, speed 0.75 / 1 / 1.25 / 1.5, mute, beat and page
  navigation, end of chapter ("Next chapter →" when the opening screen gave one). The native side calls
  `frame(frameTimeMs, stageW, stageH)` on every display frame and draws and plays exactly what it returns (audio: one
  player per cue id). URLs are refetched 5 minutes before they expire, or on `PlayerIntent.MediaFailed`.

## Screens (account and episodes)

- Settings (4th tab, gear on iOS): Keys (two provider slots, add / replace / remove with a confirmation, privacy note;
  the form's secret field is a password-type field with no autocorrection and no password AutoFill, emptied whenever
  `secretFieldResets` changes), Reading model (three options), Costs (period chips, totals, entries), Sign out (also
  still in Library).
- Title page of a comic of our catalog (`TitleDetailUiState.offersEpisodes`): each chapter row has an "Episode" chip
  next to reading it → Episode card → Watch → full-screen player. `nextChapterId(…)` (reading order) gives "Next
  chapter →"; at the end it swaps the card for the next chapter's (Android `openEpisode`, iOS `EpisodeContainer`).
- Episode card: Start / Stop on start / stop (Android lifecycle) or appear / disappear (iOS): live events only while it
  is visible. Video: Android saves through `DownloadManager` (`Movies/vovoscan`, Android 10+; the app's own Movies
  folder on 8-9); iOS downloads to a temporary file and opens the share sheet (Save Video needs
  `NSPhotoLibraryAddUsageDescription`, set in `project.yml`).

## Player rendering and audio

- Stage 9:16, centred, letterboxed; dark; controls fade after 3 s while playing (tap toggles); end card; silent hint;
  `reducedMotion` from the OS (Android "Remove animations", iOS Reduce Motion); screen kept awake while playing.
- Android (`ui/player`): one Compose `Canvas`; a `withFrameNanos` loop calls `frame(…)` and writes a state read only
  while drawing (no recomposition per frame; ~12 frames a second while paused). Draw order: backdrop, 55 % black,
  page clipped to `framing.shape` (a reused `Path`), the bubble's yellow outline, the ghost in a `saveLayer` at
  `1 - mix`, the veil, the caption (`drawText`, measured with a cached `TextMeasurer`, placement cached per beat).
  `PageImages`: Coil (cache key = URL without its query, since signed URLs change), page ≤ 2400 px, and a backdrop
  blurred once per page (72 px copy, three box-blur passes, drawn scaled up with bilinear filtering): no per-frame blur
  on any API level, so no RenderEffect. `CueAudio`: media3 ExoPlayer per cue id (plays Ogg Opus), volume per frame,
  pan through a `PanProcessor` in each player's audio sink (StereoPannerNode law), drift check every 300 ms (> 250 ms
  re-seeks), the next voice line prepared ahead, all paused on `ON_STOP`.
- iOS (`PlayerView.swift`): `TimelineView(.animation)` + `Canvas` (the Canvas must read `timeline.date`, or SwiftUI does
  not redraw it); measured at 58-60 fps in the simulator. Images decoded once with ImageIO (≤ 2400 px), backdrop blurred
  once per page with Core Image; the caption is a SwiftUI `Text` over the stage that changes only with the card. The
  controls are inserted / removed with `withAnimation` (an implicit `.animation(_:value:)` on their opacity never
  finished while the stage redraws every frame).
- **No sound on iOS yet**: AVAudioPlayer does not play the API's Ogg Opus clips (iOS 18.6 simulator: the file parses,
  `prepareToPlay()` and `play()` return false; AAC plays in the same process; same on macOS 26). AVAudioFile decodes
  them and AVAudioEngine / AVPlayer play them, so either an engine-based player (per-cue `AVAudioPlayerNode` with
  `pan`, `volume` and an `AVAudioUnitTimePitch` for the speed) or an AAC variant from the backend is needed. The
  deployment target stays iOS 16.

## iOS: known pitfalls

- Text fields: `IntentField` keeps the text on the Swift side and sends each keystroke as an intent; reading back
  the Kotlin state (asynchronous) would overwrite the input. Submit with `submitAfterCommittingInput`: iOS only
  hands over the last typed characters when focus is lost, so it dismisses the keyboard and waits for it to be gone
  (safety delay 0.8 s) before submitting; a fixed 0.1 s sometimes lost the end of a field.
- Sign-in: when submitting, resend the exact field values (intents) before `Submit`, otherwise the last
  keystroke may be missing on the Kotlin side.
- Source settings forms (fields known only at runtime): each `SettingInput` keeps its own text (`@State`, never a
  binding computed from a dictionary), copies every keystroke into the form's `[name: text]` record, does not resend
  a value Kotlin already has (a field filled on appear would mark the form edited), and Save / Confirm resend every
  recorded value before the intent.
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
