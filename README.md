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
- One database per feature; after a schema change, add a `.sqm` migration. Target SQLite 3.18 (Android 8):
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
  `ManifestCheck`, `Semver`, `VovoPackageReader`, `sha256Hex`). `feature/sources` stores them in the same `source`
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
  nothing happens while the next section loads. Native code only measures and draws: Android `PullWebView`
  (`ui/reader/BookReader.kt`) counts the finger's travel once the web view can't scroll further that way
  (`canScrollVertically`), halved like a rubber band, every event still going to the web view; iOS reads the
  WKWebView scroll view's `contentOffset` past its edges while the finger is down (`bounces`, `alwaysBounceVertical`,
  `scrollViewDidScroll` / `scrollViewWillEndDragging`), and only the hint observes it (`PullTracker`), not the web
  view. A haptic tick when the threshold is crossed (Android `GESTURE_THRESHOLD_ACTIVATE`, else `CONTEXT_CLICK`; iOS
  `.sensoryFeedback`). Reduced motion (Android "Remove animations", iOS Reduce Motion): the hint neither slides, grows
  nor fades, its bar still fills. A short fling that reaches the end does not turn (only the drag past the edge counts);
  a long drag past it does, as the threshold says. iOS rubber-bands harder than the halved drag: the same threshold
  takes a longer pull there (about 250 pt of finger travel on the iPhone 16 Pro, 240 dp on the Pixel 7).
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
  next book paused at the 30 s mark as `queued` and finished on return). iOS 26's `BGContinuedProcessingTask` (a
  user-started task with system progress UI) would fit better and is not used yet (deployment target 18.4, no iOS 26
  simulator for this app).

## Reader: modes, tap zones, settings (comics, modelled on Mihon)

- Modes (`ReadingMode`): `PagedRightToLeft`, `PagedLeftToRight`, `PagedVertical` (one page at a time), `LongStrip`
  (continuous, no gap: webtoons), `LongStripGaps` (16 dp/pt between pages); `Book` for books. `ReadingModes.resolve`:
  the mode chosen for the series (`series_mode`, per `(sourceKey, titleId)`), else the user's default for the format
  (manga / webtoon), else the format rule: vertical pages for every comic, manga and webtoon (it was right to left
  for manga and long strip for webtoons before: a mode chosen for a series or a format default chosen before is
  kept). Switching keeps the page.
- Tall pages in the paged modes (`TallPages`, as Mihon's paged viewer): a page whose fit-to-width height is more than
  1.25 × the reading area (`fitsWidth`, by its ratio: its size, or its image's once loaded) is shown fit to width and
  scrolls vertically inside its page; the next page starts after its bottom. Other pages (a manga page, Le Griot's
  800 × 1280) are fitted to the screen as before. The native side reports the current page's scroll
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
- Overlay (red next, cyan previous, ink menu, ~1.5 s): on the reader's first opening on the device, then when the
  zones change (layout, invert, or a mode that changes them), after the settings sheet closes; "Show tap zones" off
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
  into it was a thin band on iOS and cropped to its top on Android. iOS keeps the page a frame of known ratio at full
  width: in the strip's `LazyVStack`, a view sized from an image or a colour with no height proposed collapses (pages
  then took no height at all). The loader starts again when the page's URL changes (the saved copy of a chapter shows
  first, the fresh one may bring new signed links). A page that fails shows "This page didn't load." and Retry.

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

## Translated reader (`TranslationViewModel`, `iosApp/Sources/TranslationViews.swift`)

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
- Custom `ScrollTargetBehavior`: `updateTarget` also runs for programmatic scrolls, on every frame of a `ScrollPosition`
  animation, with the scroll's target as proposal: apply a gesture rule only while a finger drag is in progress, or a
  `scrollTo` is bent too. A `scrollTo(id:)` towards an item a lazy stack has not laid out yet aims at an estimated
  place (with tall items, far off). Measured frames come back as non-integers: compare offsets with a tolerance.
- AVAudioEngine: `AVAudioPlayerNode.play()` while the engine is not running raises an Objective-C exception (a
  crash): `CueMixer` starts the engine first and never plays a node in a stopped engine. Do not run audio work in a
  SwiftUI view's body other than through `PlayerAudio.sync` (cheap: volume, pan, a drift check every 300 ms).

## UI

- Colors: `Brand` (Android) and `BrandColors.swift` (iOS), copied from `brand/tokens.json`. Red = action,
  cyan = work in progress (sync, voice, downloads), yellow = highlight. Text on red: ink, never white.
- Tab bar: an icon over each label on both platforms. iOS: SF Symbols (`square.grid.2x2`, `books.vertical`,
  `square.stack.3d.up`, `gearshape`). Android: no icon library; `AppIcons` (androidApp `ui/common`) draws the same
  shapes as `ImageVector` line icons (24 × 24, 1.8 stroke), plus the download controls.
- Primary button: hard offset shadow with no blur, presses down 2 pt on tap (150 ms) with haptic feedback.
- Copy in English, short sentences, calm tone ("Invite already used."), ✦ separator.

## Branches and commits

`v1` is the main branch (pull requests target `v1`). Commit messages in English, with no footer and no
`Co-Authored-By` line.
