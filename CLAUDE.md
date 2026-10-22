# vovoscan — mobile

vovoscan's Android and iOS apps in Kotlin Multiplatform: shared logic in Kotlin, native UIs
(Jetpack Compose on Android, SwiftUI on iOS). The API (`../backend`) is the source of truth; the device keeps
a local copy so it works offline. Brand: `brand/BRAND.md` (copy of the shared brand folder) (dark only).

## Run and verify

```bash
./gradlew check :androidApp:assembleDebug
./gradlew -p build-logic test
scripts/check-fonts.sh                     # brand fonts bundled (OFL), both copies identical
cd iosApp && xcodegen && open Vovoscan.xcodeproj
cd iosApp && xcodebuild test -project Vovoscan.xcodeproj -scheme Vovoscan \
  -destination 'platform=iOS Simulator,name=iPhone 16 Pro,OS=18.6' -derivedDataPath build -only-testing:VovoscanAudioTests
```

The iOS deployment target is **iOS 18.4** (`iosApp/project.yml`): the first iOS that reads the API's Ogg Opus audio
(see "iOS audio" below).

Local API: `http://10.0.2.2:8000/` (Android emulator), `http://localhost:8000/` (iOS simulator).
No network on the iOS simulator (it shares the Mac's network and has no switch): a debug build launched with
`SIMCTL_CHILD_VOVOSCAN_OFFLINE=1 xcrun simctl launch <device> com.vovoscan.app.dev` behaves as offline
(`AppConfig.simulateOffline`: both Ktor clients fail every request, `NetworkMonitor` says offline, a `URLProtocol` fails
http(s) for AsyncImage; `file://` pages still load). The Android emulator has airplane mode.
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
- `core/`: `common` (AppResult, AppError, Freshness, StaleReason, Clock, NetworkMonitor), `network` (Ktor client, API
  envelopes, `AndroidNetworkMonitor` / `IosNetworkMonitor`), `database` (SQLDelight driver, `AppFolders`), `security`
  (Keystore / Keychain), `sources` (engine for sources read on the device), `testing` (fakes).
- `shared/` wires everything for both apps and exports the iOS "Shared" framework (SKIE).
- Rules checked at Gradle configuration time (`build-logic/.../ModuleGraph.kt`): a feature never depends on
  another; `domain` sees only `:core:common`; `presentation` sees only its `domain`; `data` does not see its
  `presentation`. Two features on the same screen (title page + follow) are wired together by the app.
- No logic in Swift or in Composables: they render the state and send intents.

## Offline first

- Screens read the local database (`Flow`), never the network directly. `refresh…` replaces the local copy with
  the server's response; without network, the copy stays on screen with a discreet banner (`Freshness.Offline`).
- Why a refresh failed (`StaleReason.of(error)`, `core/common`), so the banner says the truth: no network at all →
  "Offline: showing what's saved on this device."; a source read on the device that doesn't answer while the device is
  online (`AppError.SourceUnavailable(name)`: site down, error status, timeout) → "Project Gutenberg isn't answering
  right now. Showing what's saved." with Retry; rules that can't read the site (`SOURCE_RULES`) → "This source couldn't
  be read. It may need an update." With nothing saved, the same line without "Showing what's saved" ("You're
  offline."). Catalog (`offlineNotice`, `canRetry`; search falls back to saved titles too), title page (`notice`,
  `canRetry`), reader (the "not saved yet" line). Online or not comes from `NetworkMonitor` (Android
  `ConnectivityManager`, `ACCESS_NETWORK_STATE`; iOS `NWPathMonitor`). The internal source keeps the offline banner.
- Writes (library): applied locally right away, queued (`outbox`, one operation per title), sent by `sync()` with
  the time of the action. The server settles conflicts (last-write-wins) and returns the winning state;
  `updatedSince` fetches changes from other devices, deletions included.
- One database per feature; after a schema change, add a `.sqm` migration. A database left by a newer build (an older build
  installed over it) is rebuilt, never fatal (`DowngradeGuard`: iOS deletes and reopens it, Android empties it in
  `onDowngrade`); the iOS app used to stop at launch ("Database version 4 newer than config version 3"). Target SQLite 3.18 (Android 8):
  no `ON CONFLICT … DO UPDATE`. Session token in `SecureStore` only; everything is wiped on sign-out.

## Sources

- The official "vovoscan" source is off for a new account (backend ADR-0055): "Add a source" offers it first
  (`AddSourceUiState.officialOffers`, one tap = `EnableOfficial`: turned on and moved first, `PUT
  api/v1/catalog/sources`); it is never hidden from the list. iOS draws the on/off control as `BrandSwitch` (a plain
  button: one tap, one intent).
- `feature/sources`: the user's active sources (`GET api/v1/catalog/sources`), visible or not, in their order;
  choices applied locally then sent (`PUT`, replayed if they could not go out). At least one stays visible.
  "Sources" screen; its visible sources (in order) feed the catalog's source filter (`CatalogIntent.SourcesChanged`,
  wired by the app).
- Two engines, chosen by `sourceKey` in the remote data sources (`Routing…RemoteDataSource`):
  - `vovoscan` (internal): read through our API.
  - rule-based source (e.g. Project Gutenberg): **the device interprets the manifest and makes the requests
    itself**. Nothing goes through our API, except the `(sourceKey, titleRef)` reference of a followed title and
    its progress.
- `core/sources`: manifest (v1 contract, copy of `vovo-plugin/schema`), JSONPath, `{{settings.x}}` templates,
  CSS selectors (Ksoup) over HTML **and XML** (Atom/OPDS) responses, transforms, `RulesSource` (catalog, title
  page, pages, book URL, `categories()`). Catalog pagination: plain `{{page}}`, `startIndex` (`base + (page-1)*pageSize`)
  and `cursor` (next-page value extracted from each response, sent as a query parameter). `catalog(page, search,
  category)`: `{{category}}` is the chosen category's id (empty, so not sent, while browsing everything); `categories()`
  reads the manifest's fixed list or its own request (`list` + `item {id, name}`).
- Page images' Referer (`content.comic.referer`, `SourceManifest.imageReferer`: settings and `{{lang}}` only, http(s)):
  `RulesSource.downloadImage` sends it (downloads: `ReaderRemoteDataSource.downloadPage`); a book file
  (`download`) never does. Image loaders that know nothing of sources ask `ImageReferers.refererFor(url)` (shared
  `imageRefererFor`), a host → Referer table of the rule-based sources (first in the user's order wins a host),
  kept up to date by `ImageRefererSync` (started at launch by `VovoscanApplication` / `initKoin`): Android's Coil
  `ImageLoader` has an interceptor (`ui/common/ImageLoading.kt`), iOS registers `SourceRefererProtocol` (a `URLProtocol`
  for the shared session behind AsyncImage, replaying the request with `Referer` on a cookie-less session of its own).
  Only those hosts get it, covers included; never the API.
- `RulesHttp`: a separate client, **without the vovoscan session or cookies**, redirects followed by hand and
  limited to declared hosts (manifest + URL-type settings), rate capped by the manifest. Limits by what is fetched
  (`Transfer`): a page of the source (JSON, HTML, XML) 30 s and 5 MB, an image 2 min and 30 MB, a book file 10 min and
  100 MB; 30 s without a byte fails any of them. Responses are streamed: a file announced too large is refused from its
  headers, and a body is read at most one byte past the limit. Never the local network
  (`HostPolicy`, `core/common`): loopback, private, link-local, CGNAT, multicast and reserved IPv4 ranges (in any
  notation: `127.1`, `0x7f…`, `2130706433`), IPv6 literals, `localhost` and local-only names (`.local`, `.lan`,
  `.internal`, `.home.arpa`…) are dropped from a manifest's hosts and refused on every redirect hop; a third-party
  manifest that declares one is not installed (`PLUGIN_HOST`), a url-type setting pointing at one is refused, a URL
  with user info (`https://a.example:x@127.0.0.1/`) has no host. Repository and manifest links (`PluginLinks`) follow
  the same rule. DNS rebinding (a public name resolving to a private address) is not covered. A failure is `Network` only
  when `NetworkMonitor` says the device is offline; otherwise (exception, timeout, any error status)
  `SourceUnavailable(manifest name)`. The API client sends the session only to the API's own host.
- Regexes in shared code: escape `{` and `}` (`\{\{…\}\}`). Android's ICU engine rejects a bare `}` that the JVM
  tests and iOS accept: `Template`'s placeholder regex made every rule-based source crash the Android app.
- Catalog, title pages and chapters are keyed by `(sourceKey, id)`: two sources never mix.
- Tests: `core/sources/src/commonTest/.../Fixtures.kt` copies the manifests (change them with `vovo-plugin`).
- Third-party sources (user plugins, spec v1): added from a link (single manifest or `repo.json`, GitHub pages
  resolved to raw files) or a `.vovo` file, private to the user, not reviewed by vovoscan. `core/sources` has the
  pure parts (`PluginLinks`, `PluginFetcher`: https only, no session, ≤ 3 redirects, byte limits; `RepositoryIndex`,
  `ManifestCheck`, `Semver`, `VovoPackageReader`, `sha256Hex`). A repository entry is reviewed before anything is installed
  (`AddSourceUiState.review`: hosts, `rateLimit.requestsPerMinute`, rights, the third-party warning; `ConfirmReview` /
  `CancelReview`; an update's review names its new hosts and stands for their confirmation), like a link's or a file's
  preview; every source's details show its request cap. `feature/sources` stores them in the same `source`
  table (so `ManifestProvider` serves them), queues writes in `plugin_outbox` (`PUT/DELETE api/v1/plugins`,
  `api/v1/plugin-repositories`), keeps verified updates in `plugin_extra`. Removal goes through the
  `SourceRemovedListener` port, wired in `shared` to the catalog and reader caches.

## Catalog (every source at once)

- One grid for the selected sources ("All": every visible source, in the user's order; a source shown later joins
  in). Each source keeps its own listing per category in `catalog_entry (source_key, listing, title_id, page, rank)`
  (`listing` = the category id, `''` for everything) with `listing_cursor` (next page); migration `2.sqm` turned the
  old per-source rank into the `''` listings. `CatalogMerge.merge`: page 1 of every source mixed, then page 2 of
  every source…; within a round each next title comes from a source drawn at random, weighted by the titles it has
  left in the round, each source keeps its own order, and no source takes more than 2 places in a row while another
  has titles left (`MAX_RUN`). The draw follows a seed kept by the view model: "Load more" (the next page of every
  source that has one) appends a new round and nothing on screen moves; pull to refresh (`CatalogIntent.Refresh`)
  draws a new seed, so a new mix. Search goes to every source read (page 1 each, mixed the same way), offline per
  source as before. A manifest whose catalog request never sends `{{search}}`
  (`RulesSource.searchable`) lists the same titles whatever is typed: its results keep only names holding every word.
- Filters (`CatalogFilter`, remembered in `catalog_setting`): sources (`ShowAllSources`, `ToggleSource`: from "All" a
  tap keeps that source alone; none left or every one chosen is "All"; `CatalogSelection`), and a category by name.
  Categories: the internal source's from `GET api/v1/catalog/sources/vovoscan/categories` (`key` = id, `?category=`
  on the titles), a rule-based source's from its manifest; cached in `source_category`. Several sources: the union by
  name, trimmed and case-insensitive (`CategoryUnion`), each source asked with its own id for that name; sources
  without it are left out while the filter is on (`categoryNote`: "Project Gutenberg has no “Webtoon” category, so
  it's left out.").
- Errors per source never break the page: `problems` (one `SourceProblem` per failing source, `StaleReason`) make one
  notice line (`CatalogNotices`): no network → the offline line; one source read → its own line as before; several →
  "Project Gutenberg isn't answering right now." / "X couldn't be read. It may need an update." (Retry when one is
  down). The others' titles show; offline, every saved listing shows.
- Continue reading (`ReadingResume` port, wired in `shared`): every chapter opened is kept on the device for any title,
  followed or not (`ReaderSettingsRepository.recordRead`, `reader_setting` rows `last/<source>/<title>` = `<ms>:<page>:<chapter>`,
  erased with the account or the source), merged with the library's synced progress (`ResumePoints.merge`: the most
  recent per title). The title page's main button is `readAction` ("Continue reading →" at the chapter and page, else
  "Start reading →" at chapter 1), the chapter being read has a red edge; chapters are listed oldest first (chapter 1 on
  top) with a toggle remembered on the device (`catalog_setting` `chapters_newest_first`); `nextChapterId` follows the
  reading order whatever the list's order. The catalog shows the last title read on top (`resume`) and marks started
  cards ("Reading"). Scrolled past the search field, it folds into a floating magnifier (bottom right) that floats the
  field over the top of the catalog (iOS `onScrollGeometryChange`, Android the grid's first visible item).
- Cards carry the source's name (small badge under the format badge). Screens: a "All sources ▾" / "2 sources ▾" /
  name button and a category button (red when they narrow the catalog) open a sheet (Android `ModalBottomSheet`, iOS
  `.sheet`): "All" + the sources with checkmarks, or "All categories" + the categories.

## Reader

- `feature/reader`: a chapter ready to read (`GET api/v1/catalog/chapters/{id}`), local copy of the chapter,
  reading mode, tap zones, chapter transitions, reader settings, resume and progress.
- Books (EPUB): downloaded once (signed URL from the API, or the source's host for a rule-based source),
  read by `EpubParser` (Okio + Ksoup: `container.xml` → OPF → reading order, nav/NCX table of contents), sections
  sanitized (allowlist, images as `data:` only, links removed) and stored in the database: offline rereading.
  Displayed in a WebView / WKWebView with no JavaScript and no network, styles and CSP from `BookStyle`. A book's
  progress counts in sections.
- Books: pull to turn. Pulling up past the bottom of a section (down past its top) shows a hint that grows with the
  pull: "↑ Pull for the next section" → past the threshold, red, "Release for the next section"; letting go there
  opens it at its top. The targets are the buttons' (kept): the next section, else at the last section the next part
  ("… the next part", opens the next chapter), else "This is the last section." (no action); backwards the previous
  section (opened at its end, `sectionFromEnd`), else the previous part (opened at its last section, at its end), else
  "This is the first section.". Shared (`ReaderUiState.pullHint(overscroll)`, `ReaderIntent.PullReleased`): the pull is a
  fraction of the reading area's height, positive past the bottom; hint from 0.01, threshold `PULL_THRESHOLD` 0.15;
  nothing happens while the next section loads. Native code only measures and draws, the same way on both: the
  finger's travel from the moment the web view can't scroll further that way, halved like a rubber band, as a fraction
  of the web view's height. Android `PullWebView` (`ui/reader/BookReader.kt`, `canScrollVertically`, every event still
  going to the web view); iOS a second target on the WKWebView scroll view's own `panGestureRecognizer` (its
  translation once `contentOffset` is at an edge; `bounces`, `alwaysBounceVertical` keep the rubber band), and only
  the hint observes it (`PullTracker`), not the web view. The rubber-banded `contentOffset` is not the measure: iOS
  damps it harder than half, so the same threshold took about 250 pt of travel on the iPhone 16 Pro against 240 dp on
  the Pixel 7; with the pan's travel it is the same gesture (checked on the simulator with Alice's Adventures in
  Wonderland: 120 pt of travel past the top, −0.092, no turn; 210 pt, −0.162, the previous section opened at its end). A haptic tick when the threshold is crossed (Android `GESTURE_THRESHOLD_ACTIVATE`, else `CONTEXT_CLICK`; iOS
  `.sensoryFeedback`). Reduced motion (Android "Remove animations", iOS Reduce Motion): the hint neither slides, grows
  nor fades, its bar still fills. A short fling that reaches the end does not turn (only the drag past the edge counts);
  a long drag past it does, as the threshold says.
- Progress goes through the `ReadingProgressSink` port, wired to the library in `shared`: the reader does not
  know about `library`. It is kept only for a followed title.
- Images are signed URLs, unchanged for a day: the image cache serves pages already seen.

## Downloads (offline reading)

- In the reader slice (it knows how to get a chapter's pages from both engines): `DownloadsRepository` (domain),
  `DownloadsRepositoryImpl` + `SqlDownloadsLocalDataSource` + `PageFiles` (data), `TitleDownloadsViewModel` and
  `DownloadsViewModel` (presentation). Tables `download` (queue: state `queued | waiting | downloading | done | failed`,
  page count, error, time asked, kind `comic | book`) and `download_page` (page index → file path relative to the folder,
  bytes); migrations `3.sqm`, `4.sqm` (kind). Wi-Fi only is `download_wifi_only` in `reader_setting` (on by default).
- Storage: the app's own storage, never the photo library. Android `files/downloads`, iOS
  `Library/Application Support/Downloads` (excluded from iCloud backup), from `AppFolders` (`core/database`). Layout
  `<sha256(sourceKey)[0..24]>/<sha256(sourceKey/chapterId)[0..24]>/0007.webp` (extension from the image's signature):
  third-party ids never become paths, a source or a chapter is one folder. Paths are kept relative (the iOS container
  moves with app updates). Each page is written to `.part` then moved into place.
- Queue: in the app's `CoroutineScope`, one chapter at a time, page by page; resumed at launch (`resume()`, from
  `VovoscanApplication` / `initKoin`) and when the app comes back on screen, interrupted chapters start again at their
  first missing page. The shared queue is the only downloader and the source of truth; the platforms only keep it
  alive in the background (below) through `drain()` (run until nothing is left, or say why it waits), `pause()` (stop
  cleanly before the app is suspended: the chapter goes back to `queued`, never `failed`; waits at most 2 s) and the
  `DownloadScheduler` port (called with the network it needs whenever there is work, `replace` when Wi-Fi only
  changes). `observeActivity()` (chapters and books left, the one downloading, the block) feeds the notification
  (`DownloadText.notification`: "Downloading 2 chapters…", "Le Griot de Guerre ✦ Chapter 3 ✦ 12 / 19"). `cancelAll()`
  forgets what is not on the device yet. Each chapter is fetched again first (`remote.chapter`, saved for
  the reader), then its pages through `ReaderRemoteDataSource.downloadPage`: signed API links (internal source) or the
  source's hosts through `RulesHttp` (allowed hosts only, never the session; with the source's page-image Referer).
  Every change to a chapter's rows and files happens under one lock: a chapter removed while it downloads leaves nothing
  behind.
- Network: no connection, or "Wi-Fi only" on a metered network (`NetworkState.unmetered`: Android `NOT_METERED`, iOS not
  expensive and not constrained; a personal hotspot counts as mobile data) → `waiting`, and it carries on by itself.
  A failure while online that may pass (no answer, dropped connection, unexpected answer) is tried again by itself
  twice (after 5 s, then 30 s: in the background nobody can tap Retry, and right after a reboot the network is often
  still settling); then, or at once for a refusal, `failed` with the reason ("The download stopped. Try again.", or
  the source's refusal); "Retry" queues it again.
- Reader: local files first. `ReaderRepositoryImpl` maps a downloaded page to its `file://` URL (percent-encoded) when
  the file is on disk, else keeps the network URL. Coil and AsyncImage read `file://` URLs.
- Books: opened once, their sections are in the database; the title page and Downloads show them as "Saved on this
  device" (size of the sections), and deleting removes the sections. A book never opened (internal source or a
  rule-based source such as Project Gutenberg) can be downloaded from its title page ("Download the book", or a part's
  control; "Download the rest (n)" when some parts are saved): it joins the same queue (Wi-Fi only respected, shown in
  Downloads) as a `book` row; the queue fetches the chapter (document URL), the file (`fetchBook`, shared with
  opening), reads it into sections (`EpubParser`, stoppable between sections) and saves them with the row's removal in
  one transaction, so it then shows exactly as an opened book. "Remove from this device" appears once something is
  saved. A book file may take as long as its bytes keep coming: 10 minutes in all, 30 s without a byte is a stalled
  link (`Transfer.File` in `core/sources`; the API's own files the same in `ApiReaderRemoteDataSource.download`).
- Title page (catalog + reader, wired by the app; `TitleChapters` port in `shared`: chapters from the catalog, the
  chapter being read from the library): a control per chapter (download arrow; cyan progress ring with a stop square;
  check = downloaded, tap to delete after a confirmation; failed = retry), "Download next 5" (from the chapter being
  read, else from the first, skipping what is on the device), "Download all (n)" (when more than the next ones),
  "Delete downloads" (confirmed), a summary ("3 of 12 downloaded ✦ 1 in the queue ✦ 42 MB") and why the queue waits.
  A book: "Download the book", a control per part, "In the queue" / "Downloading" / "Saved on this device ✦ 820 KB",
  "Remove from this device" (labels and confirmation from `TitleDownloadsUiState`).
- Downloads screen (Library → "Downloads ✦ 42 MB"): by title (tap opens the title page), size per chapter and in
  total, cancel / retry / remove / delete, delete a title's downloads, the Wi-Fi only switch.
- Sign-out (`SignOutCleanup`) and a removed source (`SourceRemovedListener`) erase the rows and the files.
- Android background (`androidApp/.../downloads/BackgroundDownloads.kt`): `WorkManagerDownloadScheduler` enqueues one
  unique work "downloads" (`APPEND_OR_REPLACE` so work queued while a worker finishes is not lost, nothing added while
  one already waits; `REPLACE` when Wi-Fi only changes), constraint `CONNECTED` or `UNMETERED` (Wi-Fi only), expedited
  when the quota allows, linear backoff 30 s. `DownloadWorker` (a `CoroutineWorker`) calls `drain()`: success when the
  queue is empty, retry when it waits for a network the constraint did not foresee. It runs as a foreground service of
  type `dataSync` (manifest: `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, WorkManager's
  `SystemForegroundService` merged with the type) with a silent progress notification (channel "Downloads") and a
  Cancel action (`CancelDownloadsReceiver` → `cancelAll()`); the notification is cleared when the worker ends. Stopped
  by the system while no activity is started (network lost, time limit), the worker pauses the queue; WorkManager runs
  it again, after a kill or a reboot too (WorkManager persists the work). `POST_NOTIFICATIONS` is asked once at the
  first download (`MainActivity`, Android 13+); refused, the downloads go on and nothing shows. Limits (Android's
  rules): a foreground service cannot start from the background (Android 12+), so a worker started by WorkManager
  while the app is in the background (after a reboot, a retry, Wi-Fi back) runs as plain work, which the system may
  stop after about 10 minutes and run again later; `dataSync` foreground services get 6 hours a day (Android 15+);
  "Force stop" in the settings cancels the work until the app is opened again. Checked on the API 37 emulator: the
  notification ("Downloading 8 chapters…", "Les Heures ✦ Chapter 17 · Le Puits ✦ 6 / 8", Cancel), the queue finishing
  in the background, the worker stopped when Wi-Fi went (Wi-Fi only) and run again when it came back.
- iOS background (`iosApp/Sources/BackgroundDownloads.swift`, `BackgroundQueue` in `IosApp.kt`): leaving the app with
  work in the queue asks for `beginBackgroundTask` time (about 30 s on current iOS) and lets the shared queue go on;
  when the time runs out (or the queue waits for the network) it calls `pause()` (ending the task within 3 s whatever
  happens), so nothing is marked failed, and back on screen `resume()` carries on. It also submits a
  `BGProcessingTask` (`<bundle id>.downloads`, network required) and a `BGAppRefreshTask` (`….downloads.refresh`,
  15 min) that run `drain()` until the task expires, then pause and submit again if work is left
  (`UIBackgroundModes` `processing` + `fetch`, `BGTaskSchedulerPermittedIdentifiers`, from `project.yml`). Why not a
  background `URLSession`: pages of rule-based sources must go through `RulesHttp` (declared hosts, redirects followed
  by hand, the manifest's rate cap, never the session), which a system-run session cannot honour; every chapter needs
  an in-process step first (its page list and signed links, from the API or the rules engine) and a book a parse, so a
  background session could only carry the files of the chapter already resolved, with iOS throttling the relaunches
  needed for the next one, and the queue's states would be split between the database and `nsurlsessiond`. Limits:
  after about 30 s in the background the queue pauses until iOS runs a background task (when it chooses: often on
  power and idle, never after the user force-quits the app) or the app is opened; background tasks never run in the
  simulator (`submit` answers `BGTaskSchedulerErrorDomain` 1), so only the background time and the pause were checked
  there (iPhone 16 Pro, iOS 18.6: a Gutenberg book and Shakespeare's complete works finished in the background, the
  next book paused at the 30 s mark as `queued` and finished on return).
- iOS 26+: continued processing (`BackgroundDownloads.swift`, `#available(iOS 26, *)`; the deployment target stays
  18.4 and the above stays the fallback). A download the user starts (a chapter's control, "Download next", "Download
  all", Retry in Downloads: `TitleModel.send` / `DownloadsModel.send` call `userStarted()`) submits a
  `BGContinuedProcessingTaskRequest` (`<bundle id>.downloads.continued.<8 hex>`, the wildcard
  `….downloads.continued.*` in `BGTaskSchedulerPermittedIdentifiers` from `project.yml`; strategy `.fail`, default
  resources, no background mode needed) once the shared queue has the work, the app on screen and no network block.
  Its handler is registered just before (continued tasks are exempt from the launch-time rule). Running, it calls
  `drain()`; the system's progress UI shows `DownloadText.notification` (title and subtitle, updated as
  `observeActivity()` changes) and a progress of one unit per chapter or book (100 steps, the one downloading counted by
  its pages; work added meanwhile raises the total). It ends (`setTaskCompleted`) when the queue is empty or waits for
  the network; expired (the system, or the user stopping it from its UI), it pauses the queue if the app is in the
  background and submits the processing tasks above. While it runs, leaving the app does not start the 30 s background
  time. Limits: the system may refuse or expire it (load, battery, thermal state; a task that reports no progress for
  a while looks stalled), it is only offered while the app is on screen right after the user's action (downloads
  resumed at launch or by a background task are not "user-started"), a queue waiting for Wi-Fi does not start one, and
  the user can stop it at any time. Not checked running: the iOS 26.5 simulator (a temporary iPhone 17 Pro, deleted
  afterwards) refuses the submission with `BGTaskSchedulerErrorDomain` 1 (unavailable), like the other background
  tasks; checked there: the request is built and submitted on "Download all (24)" ("Downloading 24 chapters…",
  "Starting"), the refusal is logged and the downloads carry on in the foreground. A device on iOS 26 is needed to see
  the system progress UI.

## Reader: modes, tap zones, settings (comics, modelled on Mihon)

- Modes (`ReadingMode`): `PagedRightToLeft`, `PagedLeftToRight`, `PagedVertical` (one page at a time), `LongStrip`
  (continuous, no gap: webtoons), `LongStripGaps` (16 dp/pt between pages); `Book` for books. `ReadingModes.resolve`:
  the mode chosen for the series (`series_mode`, per `(sourceKey, titleId)`), else the user's default for the format
  (manga / webtoon), else the format's own style (reader/SPEC.md §1b, shared with the web reader): a webtoon
  `LongStrip` ("Continuous"), a manga `PagedRightToLeft`, a comic `PagedLeftToRight`.
  Switching keeps the page.
- Format recognition (`ComicFormat`: `Manga` right to left, `Webtoon`, `Comic` left to right when paged): the source's
  word first (our API's `format`; a plugin's own labels "Long Strip", "Web Comic", manhwa, manhua, BD; an original
  language `ko` / `zh` = webtoon), then the pages' shape: most sized pages taller than 2.5 × their width
  (`ComicFormat.fromPages`, re-checked as images of unknown size load) = a strip, read continuously at full width
  whatever the per-format default says (only the series' own mode overrides it). The title card names it
  (`formatLabel`: "Webtoon ✦ top to bottom").
- Continuous reader (`Glide.kt` in domain, `ReaderViewModel`, Android `ui/reader/FlowStrip.kt`, iOS `FlowStrip` in
  `ReaderView.swift`): the native side reports `StripMeasured(width, viewport)` and `StripScrolled(offset, byUser,
  settled)` (points / dp from the first page's top) and plays `GlideTo(offset, animated)`. Stops (`ReadingStops`):
  per panel (the analysis from `GET …/translations/chapters/{id}/read` `pages[].panels`, `ChapterPanels` port; else
  `Gutters.bands` from the page's luminance at 48 columns, sent as `PageLuminance`; else the page): a panel that fits
  is centred (unless already whole: only the spotlight moves), a taller one is read from its top in steps of 0.8 ×
  the screen, margin 0.03, stops never go back up. Motion: a critically damped spring (`Glide.OMEGA` 13) solved from
  each frame's time (Android `withFrameNanos`, iOS `CADisplayLink` up to 120 Hz, `CADisableMinimumFrameDurationOnPhone`);
  taps retarget keeping the velocity; a finger stops it. Past the last stop: the end card (`GoToItem`). Bars hide
  2.5 s after a Next or a scroll down, come back on a scroll up of 0.25 × the screen; a 2 pt red progress line while
  hidden; immersive system bars (Android), hidden status bar (iOS). Title card for 1.6 s on opening. Focus (off by
  default, `ReaderPreferences.focus`): the panel at full light, the rest at 0.72, gliding 380 ms. Position
  `{page, offset}` in `reader_setting` (`pos/<source>/<chapter>`, at most every 500 ms): the reading line (a third of
  the screen down) as a share of its page. "Continue reading" (`Resume`, reader SPEC §9): the stop whose panel holds
  that line is the anchor; the strip opens one stop before it (a third of a screen back without stops, or when that
  stop is more than a screen up), the anchor panel lit for 1.2 s; the placement is not saved back until the reader
  moves (no drift). The next 3 pages are prefetched. Our catalogue's comics have
  "Watch from here" (`ReadWithVoice` → `OpenEpisode(chapterId, page)` → `PlayerViewModel(…, startPage)`, the first
  beat of that page). Reduce Motion / Remove animations: jumps, no glide, no fades.
- Tall pages in the paged modes (`TallPages`, as Mihon's paged viewer): a page whose height / width is more than
  1.25 × the screen's portrait shape (long side / short side of the reading area, whichever way the device is held;
  `fitsWidth`, by its ratio: its size, or its image's once loaded) is shown fit to width and scrolls vertically inside
  its page; the next page starts after its bottom. Other pages (a manga page, Le Griot's 800 × 1280) are fitted to the
  screen as before, in landscape too: judged against the area's own shape, a manga page fit to a landscape width was
  taller than the screen and scrolled. Checked on the Pixel 7 in landscape: Le Griot's page whole, webtoon strips fit
  to width (the iOS app is portrait only). The native side reports the current page's scroll
  (`ReaderIntent.PageScroll(item, tall, atTop, atBottom)`, like `StripEdges`) and runs commands: Next on a tall page
  not at its bottom is `ScrollBy(+tap scroll amount)` inside the page (never past its edge), at its bottom the next
  page; Previous mirrors it, and the previous page opens at its bottom (`GoToItem.atBottom`). Volume keys follow the
  same path. A report for another item than the current one is ignored (cleared when the item changes).
- Tap zones (`TapZones`, pure, `domain`): a 3 × 3 grid of the reading area (thirds); the native side sends where the
  finger went up as `ReaderIntent.Tap(x, y)` (0..1, origin top left) and nothing else. Rows top to bottom
  (P = Previous, N = Next, M = Menu): Right and left `PMN/PMN/PMN`, L-shaped `PPP/PMN/NNN`, Kindle-ish
  `MMM/PNN/PNN`, Edge `NMN/NMN/NPN`, Disabled all `M`. Default: Right and left for horizontal pages, L-shaped for
  vertical pages and strips. Invert None / Horizontal (columns) / Vertical (rows) / Both; right-to-left pages mirror
  the columns, and a horizontal invert flips that back (XOR). `TapZones.map` merges the cells into the overlay's
  rectangles and labels.
- Actions: paged → `ReaderCommand.GoToItem` (animated); strips → `ScrollBy(±fraction)` of the reading area's height
  (50 / 75 / 90 %, default 75 %); Menu toggles the bars; Next / Previous also hide them. `ReaderUiState.items`: the
  previous-chapter card, the pages, the next-chapter card (page i = item i + 1). Past the last page: the card
  ("Finished: Chapter 3" / "Next: Chapter 4 · name" / "This is the last chapter for now."), a further Next opens the
  next chapter; before the first page the same backwards, and the previous chapter opens at its last page
  (`ReaderViewModel.START_AT_END`). Strips report their edges (`StripEdges`): Next at the bottom opens the next
  chapter. Chapter names come through the `ChapterHeadings` port (wired in `shared` to the title page kept by the
  catalog). Commands carry a `seq`; the native side starts them and answers `CommandHandled(seq)`.
- Single taps act on touch-up (measured 6-19 ms from touch-up to the intent on the iOS simulator). Double tap to zoom
  (off by default; 2×, pan by dragging, scrolling paused while zoomed) makes single taps wait for a possible second
  tap (~370 ms). A drag, fling or pinch is never a tap (the scroll view takes it first). Taps faster than the scroll
  animation add up.
- "How to read" guide (reader SPEC §10, `ReaderCoach`, `CoachKind` Manga / Webtoon / Comic / Book): the first chapter
  of each format the account opens shows a static card (the real tap zones, one line per gesture for the mode it
  reads in); the first tap anywhere closes it and is not a page turn; the "?" in the top bar (`ShowCoach`) brings it
  back. Seen once per format per account (`CoachSync`, wired in `shared` to `GET /settings/reading` `coached` and
  `PUT /settings/reading/coached/{kind}`; books stay on the device), kept on the device too (`ReaderPreferences.
  coachSeen`, `reader_setting` "coachSeen") so it never flashes while the account's list loads; seen offline is told
  to the account when a reader next opens.
- Overlay (red next, cyan previous, ink menu, ~1.5 s): on the first chapter of a kind whose coach was already seen,
  then when the zones change (layout, invert, or a mode that changes them), after the settings sheet closes; "Show tap zones" off
  never shows it.
- Settings sheet (gear in the top bar), kept on the device in the reader's database (`reader_setting` key-value
  table, `series_mode`; migration `2.sqm`), no backend: mode for this series (+ Reset to default; "Default for this
  series: Vertical pages." unless a format default was chosen), default mode for manga / webtoon, tap zones, invert,
  show tap zones (on), tap scroll amount (75 %, strips and tall pages), double tap to zoom (off), keep screen awake
  (on), Android only: volume keys turn pages (off; down = next, up = previous) and invert them (off).
  Sign-out and a removed source erase the series modes; the device-wide preferences stay.
- Android: `HorizontalPager` (reversed for right to left) / `VerticalPager` / `LazyColumn`, `detectTapGestures`
  around them, volume keys through `MainActivity.onKeyDown`/`onKeyUp` while the reader is on screen. Pages taller than
  4096 px, and pages of unknown height, are decoded as software bitmaps (a hardware bitmap of an 800 × 14 000 webtoon
  page never drew). A tall page is a `verticalScroll` inside its pager page (`PagedPage`): nested scrolling scrolls the
  page first, the rest of the drag moves the pager, a fling stops at the page's edge (the pager's own rule). Pages
  before the current one wait at their bottom, pages after it at their top, so a swipe back shows the end of the
  previous page. iOS: horizontal pages are a paging `ScrollView` (`scrollTargetBehavior(.paging)`, right-to-left layout
  direction) and a tall page an inner vertical `ScrollView` (`TallPageView`, another axis). Vertical pages are one
  `ScrollView` in which a tall page takes its full height (`VerticalPages`): UIKit does not hand a vertical swipe from
  an inner scroll view to an outer one, so two nested ones would never chain. Where a drag comes to rest is shared
  (`TallPages.restingOffset`, a custom `ScrollTargetBehavior`, for user drags only): inside a tall page the scroll
  stays in it and a fling stops at its edge; from an edge or past it, it is a page turn when the rest point is half
  the screen past the edge (`TURN_FRACTION`), else back to the edge, one page per gesture. The stack is a `VStack`,
  not lazy (a lazy stack's estimated positions for pages not laid out yet made scrolls to the next page stop short or
  not move), and only pages within 2 of the current one hold their image (`PageImage.active`). Strips: `ScrollView` +
  `ScrollPosition`; one `SpatialTapGesture` on the container.
- Page size (`ReaderPage.hasSize`): our pages come with their size, so the space is reserved and nothing jumps. A source
  read on the device usually sends none (0 × 0): the page shows a placeholder (0.7, portrait) until the image has loaded,
  then takes **the image's own shape** (Android: Coil's `onSuccess`; iOS: `PageLoader`, which fetches through the shared
  session so the cache and `SourceRefererProtocol` still apply). Never the placeholder's shape: a webtoon strip squeezed
  into it was a thin band on iOS and cropped to its top on Android. Android decodes such a page for the screen's width,
  whatever its height (`size(Size(width, Dimension.Undefined))`): sized for the placeholder's screen-fitted box, a strip
  came out a few hundred pixels wide and was blurred once it filled the width.
- Resume with pages of unknown size (and as they change shape). The reader opens at the saved page in every mode and
  stays there while pages go from the placeholder to their real height. Cause of the bug ("page 1 / 7" instead of the
  saved page 5, right to left, a rule-based source): `SqlReaderLocalDataSource.observe` combined two queries (the
  chapter row, its page rows); a chapter saved for the first time was seen once with the pages it had before (none),
  and `ReaderViewModel.show` clamped the resume page to that empty list, so the reader opened at page 1 and saved it.
  Fixed twice: the chapter and its pages are read together (one transaction whenever either table changes), and the
  view model keeps the resume page while a chapter has no pages (tests: `SqlReaderLocalDataSourceTest`,
  `ReaderViewModelTest.resume…`). Sources sending sizes rarely reached it (their chapters were usually saved already).
  Native side: horizontal pages are fixed-width items (nothing moves); Android's `LazyColumn` and pagers keep their
  first item; iOS vertical pages and strips keep an anchor (`PagedLayout.anchor`: the item read and the offset inside
  it, set at opening, after a gesture or a command) and, at rest, follow it when frames change, from the anchor's frame
  (several pages growing in one layout pass each moved the old offset-plus-difference). Checked with Reaper's chapter 1
  at size 0 (and with its sizes back): iOS right to left, left to right, vertical pages, long strip, strip with gaps;
  Android vertical pages, right to left, long strip. iOS keeps the page a frame of known ratio at full
  width: in the strip's `LazyVStack`, a view sized from an image or a colour with no height proposed collapses (pages
  then took no height at all). The loader starts again when the page's URL changes (the saved copy of a chapter shows
  first, the fresh one may bring new signed links). A page that fails shows "This page didn't load." and Retry; Retry
  sits under the reader's tap detector and takes the tap itself (the button consumes it, no tap zone fires; checked
  on the Pixel 7 with a page whose file was missing: the page loaded on Retry and took its tall shape).

## Large screens (iPad, tablets, foldables)

- Shared rules, never the device's name: `core/common/Adaptive.kt` (`Adaptive.of(width, height, hinge)` → width class at
  600 / 840 dp, navigation BottomBar / Rail / Sidebar, tile width, readable width 720, two panes, posture Flat / Book /
  Tabletop, a separating vertical hinge) and `feature/reader/domain/Spreads.kt` (`Spreads.fit`: two pages from 600 wide
  and 1.15 width / height, or across a vertical hinge; `Spreads.of`: the cover, chapter cards, double spreads and strips
  (`STRIP_ALONE`, height ≥ 2 × width) stand alone, other pages pair; `ReaderKeys`: arrows follow the page direction,
  space / page keys turn, Escape closes).
- Reader: paged horizontal modes show `ReaderUiState.spreads` (setting "Two pages side by side", `doublePages`, on);
  the native side reports `Viewport(width, height, verticalHinge)` and keys as `ReaderIntent.Key`; `positionLabel`
  "4–5 / 19"; `ReaderCommand.Close` for Escape.
- iOS: native on iPad (`TARGETED_DEVICE_FAMILY` 1,2, every orientation), `TabView` with `Tab` and `.sidebarAdaptable`,
  ⌘1-4 (`TabShortcuts`), `.readableWidth()` on text screens, cards lift under the pointer (`.hoverEffect(.lift)`),
  spreads in `HorizontalPages`, keys through `onKeyPress` (`ReaderKeyMap`).
- Android: `AdaptiveWindow` (`ui/common/WindowLayout.kt`: window size + Jetpack WindowManager `FoldingFeature`, the only
  way to know the hinge; `androidx.window`) provides `LocalAdaptive`; tabs as a rail or a sidebar (`SideTabs`) from
  600 dp; the activity handles size, orientation and fold changes itself (`configChanges`), so folding or unfolding
  keeps the screen and the reading position; spreads in `HorizontalPager` with the hinge's gap between the pages;
  keys through `onPreviewKeyEvent`.

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
- Languages (`?lang=` on every episode call, "" = the original; `EpisodeDto.language`, `audio` `dub | sub`): the card
  has a searchable language picker (`EpisodeIntent.ChooseLanguage`, `LanguageQuery`; `Language.ALL` in `core/common`,
  the server's `core/languages.py` with its `dub` / `mp4` / `beta` flags): "Dubbed" when ElevenLabs speaks it, else
  "Subtitled" (the original voices, captions in it). Each language is its own episode, stored under `<chapterId>~<lang>`
  (`storeKey`; the original keeps the bare chapter id), quoted with its `lang`, run with a TRANSLATE stage
  (`translating`). The player opens in the card's `language`, and "Next chapter →" opens the next card in it.

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

## Account access: feature flags, credits, studio

- `/auth/me` (`UserDto`) reads both server generations: `displayName`/`roleName` (v1) and `name`/`role` (unified API),
  plus `isSuperAdmin`, `permissions`, `features`, `emailVerified`. `Session.has(feature)`: a super-admin has every
  feature. `Session.isCreator` = `titles:create`.
- Safe mode (per account, on by default): `showAdult` on `/auth/me`, switched in Settings › Preferences (`PreferencesViewModel`:
  on applies at once; off asks "I'm 18 or older" first, then `PUT api/v1/settings/content {showAdult, confirmAdult}`;
  online only, never queued). Ratings: `ContentRating` (`core/common`: `safe | suggestive | erotica | pornographic`,
  null = unknown); the rule `ContentRating.shown`: safe mode shows only `safe` (unknown hidden too). The server filters
  what it serves; the device applies the same rule to titles read on the device: `RulesSource` reads
  `catalog.item.contentRating` (mapped by the manifest's `ratings.map`, a value off the scale and unmapped = unknown;
  nothing read = `ratings.default`), stored in `title.content_rating` (catalog `3.sqm`), and `CatalogViewModel` filters
  listings and search through the `ContentPolicy` port (wired in `shared` to the session; signed out = safe) with one
  line when titles were hidden (`safeNote`). A manifest without ratings has every title unknown, so hidden in safe mode.
- `video` gates episodes: the `EpisodeAccess` port (episodes domain, wired in `shared` to the session) turns the
  episode card into "Episodes are coming soon." with no action (`EpisodeUiState.comingSoon`); a server answer
  `FEATURE_DISABLED` / `FEATURE_COMING_SOON` does the same. The server checks every call again.
- Credits (runs on the platform's keys; own keys cost none): `WalletRepository` (account slice, in memory only)
  reads `GET api/v1/me/wallet` `{balanceCredits, usesOwnKeys, dailyCapReached}`; a 404 means a server without
  credits (nothing shown). `POST api/v1/quotes {kind, chapterId, lang?}` → `{credits, maxCredits, coveredByOwnKeys,
  alreadyAvailable, fundable, metered?, unfunded?}` feeds the `RunQuotes` port: per provider (`RunCost.funding`) the
  card says own key, platform key paid in credits, or nothing (that step is skipped: bubbles unread, lines silent);
  never inferred from a zero charge. The cost shows next to Prepare (`costLine`; none for an unfunded run, "Not enough
  credits" when the wallet can't hold `maxCredits`).
  Errors: `402 INSUFFICIENT_CREDITS`, `503 PLATFORM_PAUSED`, `429 DAILY_CAP` (kept as `Rejected`, not a rate limit,
  by `errorOf`), each with a calm line (`EpisodeText.creditError`). `WalletViewModel` = the credits card in Settings.
- The creator studio is web only: Settings links to `WEB_BASE_URL` + `contribute` (`project.yml`), "Creator studio"
  for creators, "Publish your own series" otherwise. The apps never upload, translate or schedule releases.
- A new account has no source turned on (the official one starts hidden): `SourcesUiState.needsSource` (no source, or
  every one switched off) makes the catalog and the library show "Start with a source." / "Turn on a source." with an
  action to the Sources tab (`OpenTabAction` in the SwiftUI environment, the tab state on Android), never a skeleton;
  a catalog with nothing to read settles `Fresh` and empty.

## iOS finish (`iosApp/Sources/Premium.swift`)

- Launch: the mark is stamped on the page (spring, haptic, speed lines), then the app or the sign-in fades in.
- Sign-in: a printed-manga hero (speed lines, red halftone, ドン!, "Invite only" kicker), password AutoFill content
  types, an error haptic.
- Catalog: covers open their title page with the iOS 18 zoom transition (`matchedTransitionSource` +
  `.navigationTransition(.zoom)`), tiles sink when pressed (`PressableCardStyle`), rise in one after the other
  (`riseIn`), skeleton tiles shimmer while the first page loads.
- `EmptyStateCard` (onomatopoeia, one sentence, one action), `Sticker`, `ScreenHeader`, `Haptics`, `HalftoneBackground`,
  `SpeedLines` (deterministic, drawn with `Canvas`). Ink tab and navigation bars, red selection, a selection haptic per
  tab. Every animation is skipped under Reduce Motion.

## Translated reader (`TranslationViewModel`, iOS `TranslationViews.swift`, Android `ui/reader/{Lettering,TranslationSheet}.kt`)

- Chapters of our catalog only (`sourceKey == "vovoscan"`): a language button in the reader's top bar opens a
  searchable picker (own name, English name or code), languages already made first ("Ready": shared, free).
- `TranslationsRepository` (reader slice, online only): `GET api/v1/translations/chapters/{id}` (`{translations:
  [{language, status, progress…}]}`), `POST …/{id} {language}` (200 exists or runs: shared, free; 202 queued; 402 / 429
  / 503 from credits), `GET …/{id}/read?lang=` (pages `width`/`height` + `texts` `{id, bbox, text, fill, ink, round,
  area}`, boxes `[x, y, width, height]` in page pixels). A queued language is polled every 4 s until ready. Corrections
  (`PUT …/{lang}`) are the web studio's, never the app's.
- `LetteringLayout` (reader domain) is the web reader's `lettering.ts`: paint patches over the original letters (the
  balloon's interior when known, ellipse for round balloons) and the rectangle the translation is fitted in, as
  fractions of the page. iOS draws them in `LetteringOverlay` over each `PageImage` (any mode, any zoom). `Language.ALL`
  (`core/common`) mirrors the server's `LANGUAGES` (48 entries).

- Any time, from the reader's bar: a language already made switches at once (free); one nobody made yet is quoted first
  (`TranslationQuotes` port, wired in `shared` to `POST api/v1/quotes` kind `translation`): `TranslationOffer` "Translate
  into Yorùbá?" with "Your own key pays for it", "About 12 credits", or, when nobody can pay, "Add a key" (the keys open
  inside the sheet, the reader stays); nothing is asked before "Translate". Then progress, and the page swaps its
  lettering. Android draws it with `LetteringOverlay` over every page (`LocalPageLettering`, in a box of the page's
  shape).

## Screens (account and episodes)

- Settings (4th tab, gear on both): Keys (two provider slots, add / replace / remove with a confirmation, privacy note;
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
- iOS audio (`iosApp/Sources/Audio`): AVAudioPlayer refuses the API's Ogg Opus clips (the file parses,
  `prepareToPlay()` / `play()` return false), AVAudioFile decodes them, so the player plays them on AVAudioEngine (the
  backend keeps Ogg Opus only). `PlayerAudio` (one per player screen, owned by `StageDriver`) gets each frame's cues
  (`CueState`, a Swift copy of `AudioCue`) and the play state / speed. Graph: one `AVAudioPlayerNode` per cue id
  (`volume` = gain, `pan` = pan, set every frame) → a cue `AVAudioMixerNode` → [`AVAudioUnitTimePitch`, rate = speed,
  pitch kept] → main mixer → output. `CueMixer`: a node is attached and starts at `offsetMs` when its id appears
  (the rest of the clip shares the clip's memory, `bufferListNoCopy`), stopped and detached when it goes; a loop
  schedules the rest of the clip from the offset, then the whole clip with `.loops`: back to back, sample-exact. Drift:
  every 300 ms, a node more than 250 ms from its cue's offset (circular for loops) is scheduled again from the offset
  (the Android rule). Pause pauses every node and the engine; the engine starts (and the session activates) only when a
  node must play. The time-pitch stage is in the graph only away from 1×: it reads ~4 000 frames (~80 ms) ahead of
  what is heard, which would delay gain, pan and pause by as much; switching speed rewires it with the engine paused
  for a moment. A new node (attached and connected while the engine runs) is heard 0 to 3 render cycles after it is
  started (0-50 ms in the offline tests, 800-frame cycles); the drift rule tolerates it.
  `ClipStore`: each clip downloaded once to `Caches/EpisodeAudio/<sha256 of the URL without its query>.ogg` (trimmed to
  200 MB, oldest first), decoded whole with AVAudioFile to float PCM at 48 kHz (AVAudioConverter if the rate differs;
  voices mono, beds/music stereo, more channels mixed down to two), kept in an LRU of 96 MB (about 4 minutes of
  stereo); the next beat's voice line is warmed (`prefetchUrls`). A download or decode failure sends
  `PlayerIntent.MediaFailed(url)` once (not retried for 30 s unless the URL changes). Audio session: `.playback` (plays
  with the silent switch on); an interruption pauses (and resets the nodes), its end resumes if the episode was
  playing and the system says `shouldResume`; headphones / Bluetooth gone (`oldDeviceUnavailable`) pauses; an engine
  configuration change resets the nodes (made again at their offsets on the next frame); a media-services reset
  rebuilds the engine; closing the player stops the engine and deactivates the session (`notifyOthersOnDeactivation`).
- iOS audio tests (`iosApp/Tests/Audio`, target `VovoscanAudioTests`, in the `Vovoscan` scheme): `Sources/Audio`
  compiled as it is, an `AVAudioEngine` in offline manual rendering driven at 60 frames a second, Ogg Opus fixtures
  made the backend's way (`make-fixtures.sh`: ffmpeg libopus 48 kHz, a mono `say` voice line, a stereo 2.025 s loop).
  They check the decoding (Apple's decode equals ffmpeg's, Opus pre-skip and end trim applied), start at the offset,
  gain and pan, the loop seam (output bit-identical to the clip played back to back, no silent millisecond), speed
  0.75 / 1.5 keeping the pitch (tone and voice fundamental), pause / resume, drift re-sync, removal, failures, the LRU.
- Ogg Opus on iOS needs **iOS 18.4**: the audio tests pass on the iOS 18.4, 18.6 and 26.5 simulators (and AVAudioFile
  reads the clips on macOS 26); on the iOS 18.2 simulator `AVAudioFile` refuses them (`ExtAudioFileOpenURL` error
  `'typ?'`, kAudioFileUnsupportedFileTypeError: no Ogg reader before 18.4). No iOS 16 or 17 runtime was installed, but
  they are older than 18.2. So the deployment target went from iOS 16 to 18.4; a lower one would play episodes silent
  (every clip reported as failed) unless the backend also serves another format (AAC / Opus in CAF or MP4).
- The timeline's loop length (`LoopCue.durationMs`, ffprobe's container duration) includes the Opus pre-skip
  (6.5 ms), so it is a few ms longer than the decoded loop: a bed slowly drifts from the clock and is re-synced once
  250 ms away (after ~40 turns of the loop). Android has the same; fixing it is a backend change (the decoded length).

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
- `scrollTo(id:anchor: .top)` towards an item taller than the screen (a tall page in `VerticalPages`, a non-lazy
  `VStack` with a custom scroll target behaviour) came to rest at the item's bottom: the opening position is held
  from the measured frame (`PagedLayout.anchor`, `opening`) until the reader moves.
- Custom `ScrollTargetBehavior`: `updateTarget` also runs for programmatic scrolls, on every frame of a `ScrollPosition`
  animation, with the scroll's target as proposal: apply a gesture rule only while a finger drag is in progress, or a
  `scrollTo` is bent too. A `scrollTo(id:)` towards an item a lazy stack has not laid out yet aims at an estimated
  place (with tall items, far off). Measured frames come back as non-integers: compare offsets with a tolerance.
- AVAudioEngine: `AVAudioPlayerNode.play()` while the engine is not running raises an Objective-C exception (a
  crash): `CueMixer` starts the engine first and never plays a node in a stopped engine. Do not run audio work in a
  SwiftUI view's body other than through `PlayerAudio.sync` (cheap: volume, pan, a drift check every 300 ms).

## Languages, tutorial, account (i18n/SPEC.md, shared with the web)

- UI languages: English and French (`UiLocales`), catalogues `core/common/i18n/{en,fr}.json` (ICU messages, keys shared
  with the web's `apps/web/messages`; generated into Kotlin). `t(key, pairs)` / `I18n.text` / `I18n.textWith`;
  `LocalFormat` and `PlatformFormats` (region name, dates, times) follow the region. French: vous, typographic ’, a
  narrow no-break space (U+202F) before `: ; ? !` (`CataloguesTest` checks every string). Never a literal on screen.
- The UI language is not a content language: chapters are translated into the **reading language**
  (`GET/PUT api/v1/settings/reading` `targetLanguage`, `ReadingSettingsViewModel`, `LanguagePicker` over `Language.ALL`).
- `UiLanguage` (`core/common`): the account's `locale`/`region` win, else the choice saved on the device
  (`DevicePreferences`: SharedPreferences `vovoscan_device` / NSUserDefaults `vovoscan.*`), else the device's languages.
  `LocaleSync` (`shared`) applies the account's on every session change. The API client sends `Accept-Language` (the UI
  language) to the API host only. A change rebuilds the screens: iOS `LocaleRoot` (`.id(locale)`), Android
  `recreate()` (ViewModels survive; labels are getters read on draw).
- Sign-up (`SignInViewModel`): the UI language chips, and in invite mode the age box (`auth.ageConfirm`, the region's
  minimum age; `422 AGE_NOT_CONFIRMED`); the redeem sends locale, region, time zone and `ageConfirmed`.
- Tutorial (`OnboardingViewModel`, `ONBOARDING_VERSION` 1): welcome, language, reading, sources, credits, done; Skip on
  every step, Back from the second, the step kept in `DevicePreferences` (a language change rebuilds the screen). Shown
  when `/auth/me` says `onboarding.due` (or replayed from Settings › Preferences: `SessionViewModel.replayTutorial`);
  finishing or skipping sends `onboardingCompleted`. The sources step offers the official "vovoscan" source first
  (off by default, ADR-0055; one tap puts it first and visible, `AppOnboardingServices.turnOnOfficial`), then "Add a
  source"; the credits step explains own keys vs credits and opens the keys screen.
- Tutorial scenes (iOS `OnboardingScenes.swift`, Android `ui/onboarding/OnboardingScenes.kt`, the same on both):
  welcome opens cold on a manga page whose three panels slam in (a haptic each, ~1.6 s, the mark stamped last); the
  reading step has a live translated page (`DemoLines.balloon`: one balloon, "行くぞ！" wiped and lettered again in the
  reading language in its reading direction, on a loop; a line written by hand for 30 languages, any other shows
  the app's language and says so, never a guessed translation); credits count the wallet up (`balanceLabel`); done
  ends on the official source's first title (`OnboardingServices.featured`, loaded when the source is turned on) with
  "Read …" opening it in the catalog. Reduce Motion / animations off: every scene at its final state.
- Brand files: iOS `iosApp/Assets.xcassets` (`AppIcon` 1024 px any/dark/tinted, `BrandLogo` = every `LogoMark`,
  `LaunchMark` on `LaunchBackground` = the launch screen), Android `res/mipmap-anydpi-v26/ic_launcher.xml` (adaptive:
  `drawable/ic_launcher_background`, `ic_launcher_foreground`, `ic_launcher_monochrome`), `mipmap-*/ic_launcher.png`,
  `drawable-nodpi/brand_logo.png` (`LogoMark`), Android 12+ splash `drawable/splash_mark` in `values-v31/themes.xml`.
  The mark is the Fukidashi balloon (brand direction A, from the brand kit); replacing the files is enough.
- Profile changes (`AuthRepository.updateProfile`, `PATCH api/v1/me`): applied on the device at once; offline they are
  merged into one pending patch (`pending_profile` in the secure store) sent before the next `GET /auth/me`; a refusal
  restores the server's profile.
- Account (`AccountViewModel`, `AccountRepository`): name, email change (password, link sent to the new address),
  password, devices (`GET/DELETE api/v1/me/sessions`, revoke others), deletion with a 30-day grace period
  (`POST/DELETE api/v1/me/deletion`; a yellow banner with "Keep my account" on every screen meanwhile; the owner can't).
  Passwords stay in the native fields and travel only in the intent that sends them (`secretFieldResets` empties the
  fields). `401 WRONG_PASSWORD` is a refusal, never a sign-out: `errorOf` checks `data.error` first.
- Screens: iOS `OnboardingView.swift`, `AccountView.swift` (Account, Preferences); Android `ui/onboarding`,
  `ui/account`; Settings opens both (Account, Preferences rows).

## UI

- Colors: `Brand` (Android) and `BrandColors.swift` (iOS), copied from `brand/tokens.json`. Red = action,
  cyan = work in progress (sync, voice, downloads), yellow = highlight. Text on red: ink, never white.
- Tab bar: an icon over each label on both platforms. iOS: SF Symbols (`square.grid.2x2`, `books.vertical`,
  `square.stack.3d.up`, `gearshape`). Android: no icon library; `AppIcons` (androidApp `ui/common`) draws the same
  shapes as `ImageVector` line icons (24 × 24, 1.8 stroke), plus the download controls.
- Primary button: hard offset shadow with no blur, presses down 2 pt on tap (150 ms) with haptic feedback.
- Copy in the UI language (catalogues above), short sentences, calm tone ("Invite already used."), ✦ separator.

## Branches and commits

`v1` is the main branch (pull requests target `v1`). Commit messages in English, with no footer and no
`Co-Authored-By` line.
