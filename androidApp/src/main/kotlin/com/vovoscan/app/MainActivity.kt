package com.vovoscan.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.vovoscan.app.ui.account.AccountScreen
import com.vovoscan.app.ui.account.PreferencesScreen
import com.vovoscan.app.ui.catalog.CatalogScreen
import com.vovoscan.app.ui.common.AdaptiveWindow
import com.vovoscan.app.ui.common.AppIcons
import com.vovoscan.app.ui.common.LocalAdaptive
import com.vovoscan.app.ui.downloads.DownloadsScreen
import com.vovoscan.app.ui.episode.EpisodeScreen
import com.vovoscan.app.ui.library.LibraryScreen
import com.vovoscan.app.ui.onboarding.OnboardingScreen
import com.vovoscan.app.ui.player.PlayerScreen
import com.vovoscan.app.ui.reader.ReaderScreen
import com.vovoscan.app.ui.settings.SettingsScreen
import com.vovoscan.app.ui.signin.SignInScreen
import com.vovoscan.app.ui.sources.AddSourceScreen
import com.vovoscan.app.ui.sources.SourceDetailScreen
import com.vovoscan.app.ui.sources.SourcesScreen
import com.vovoscan.app.ui.theme.Brand
import com.vovoscan.app.ui.theme.VovoscanTheme
import com.vovoscan.app.ui.title.TitleScreen
import com.vovoscan.core.common.INTERNAL_SOURCE_KEY
import com.vovoscan.core.common.Navigation
import com.vovoscan.core.common.i18n.I18n
import com.vovoscan.feature.auth.presentation.DeletionBannerUi
import com.vovoscan.feature.auth.presentation.SessionViewModel
import com.vovoscan.feature.catalog.presentation.TitleDetailViewModel
import com.vovoscan.feature.reader.domain.DownloadsRepository
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

class MainActivity : ComponentActivity() {
    /** Set by the reader while it is on screen: true when it used the volume key (up = volume up). */
    var volumeKeys: ((up: Boolean) -> Boolean)? = null
    private var volumeKeyTaken = false
    private val downloads: DownloadsRepository by inject()

    // Android 13+: the downloads notification needs the permission, asked once at the first download. Refused, the
    // downloads go on all the same (the foreground service shows in the system's task manager only).
    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onKeyDown(
        keyCode: Int,
        event: KeyEvent,
    ): Boolean {
        val handler = volumeKeys
        if (handler != null && isVolumeKey(keyCode)) {
            // A held key repeats: one page per press.
            if (event.repeatCount > 0 && volumeKeyTaken) return true
            volumeKeyTaken = handler(keyCode == KeyEvent.KEYCODE_VOLUME_UP)
            if (volumeKeyTaken) return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(
        keyCode: Int,
        event: KeyEvent,
    ): Boolean {
        if (isVolumeKey(keyCode) && volumeKeyTaken) {
            volumeKeyTaken = false
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun isVolumeKey(keyCode: Int) = keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            VovoscanTheme {
                AdaptiveWindow { Root() }
            }
        }
        // A new app language rebuilds every screen in it (the ViewModels survive; their labels are read on draw).
        lifecycleScope.launch { I18n.locale.drop(1).collect { recreate() } }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    downloads.observeActivity().first { it.busy }
                    askNotificationsOnce()
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Back on screen: a queue paused in the background carries on.
        downloads.resume()
    }

    private fun askNotificationsOnce() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val prefs = getSharedPreferences("downloads", Context.MODE_PRIVATE)
        if (granted || prefs.getBoolean(NOTIFICATIONS_ASKED, false)) return
        prefs.edit().putBoolean(NOTIFICATIONS_ASKED, true).apply()
        askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private companion object {
        const val NOTIFICATIONS_ASKED = "notifications_asked"
    }
}

/** The four tabs, an icon over each label (the iOS tab bar's symbols, drawn in `AppIcons`). */
private enum class Tab(
    private val labelKey: String,
    val icon: () -> ImageVector,
) {
    Catalog("nav.catalog", { AppIcons.Catalog }),
    Library("nav.library", { AppIcons.Library }),
    Sources("nav.sources", { AppIcons.Sources }),
    Settings("nav.settings", { AppIcons.Settings }),
    ;

    /** Read on every composition: the activity is recreated when the app's language changes. */
    val label: String get() = I18n.text(labelKey)
}

/**
 * Root: sign-in, or the Catalog / Library / Sources / Settings tabs, with the title page, the source screens, the
 * reader, an episode card and the full-screen episode player on top.
 */
@Composable
private fun Root(session: SessionViewModel = koinViewModel()) {
    val user by session.session.collectAsStateWithLifecycle()
    val tutorial by session.tutorial.collectAsStateWithLifecycle()
    val deletion by session.deletionBanner.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(Tab.Catalog) }
    // Open settings screen (Settings tab): "account" or "preferences".
    var openSettings by rememberSaveable { mutableStateOf<String?>(null) }
    // Open title page: "source|title".
    var openTitle by rememberSaveable { mutableStateOf<String?>(null) }
    // Downloads screen open (Library tab).
    var openDownloads by rememberSaveable { mutableStateOf(false) }
    // Open sources screen (Sources tab): "add-source", "add-source|repository id" or "source-detail|source key".
    var openSource by rememberSaveable { mutableStateOf<String?>(null) }
    // Open reader: "source|title|chapter|start page|language" (`ReaderViewModel.START_AT_END`: from its last page; the
    // language, optional, for a source's chapter reopened on its translated copy).
    var openReader by rememberSaveable { mutableStateOf<String?>(null) }
    // Open episode card (over the title page): "chapter|language" (language empty = the original).
    var openEpisode by rememberSaveable { mutableStateOf<String?>(null) }
    // Open player (full screen): "chapter|next chapter or empty|language or empty|start page or empty".
    var openPlayer by rememberSaveable { mutableStateOf<String?>(null) }
    if (user != null && tutorial.shown) {
        // The first-launch tour, full screen over everything (replayable from Settings › Preferences).
        Column(
            Modifier
                .fillMaxSize()
                .background(Brand.Ink)
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            OnboardingScreen(
                onClose = session::tutorialClosed,
                onOpenLibrary = {
                    tab = Tab.Library
                    openSettings = null
                },
                onOpenTitle = { source, id ->
                    tab = Tab.Catalog
                    openSettings = null
                    openTitle = "$source|$id"
                },
            )
        }
        return
    }
    val player = openPlayer?.split("|")
    if (user != null && player != null) {
        val language = player.getOrNull(2).orEmpty()
        BackHandler { openPlayer = null }
        PlayerScreen(
            chapterId = player[0],
            nextChapterId = player[1].ifEmpty { null },
            language = language,
            startPage = player.getOrNull(3)?.toIntOrNull(),
            onClose = { openPlayer = null },
            onNextChapter = { next, watched ->
                // The next chapter's episode card, in the language just watched: watch it from there, or prepare it.
                openPlayer = null
                openEpisode = "$next|$watched"
            },
        )
        return
    }
    Column(
        Modifier
            .fillMaxSize()
            .background(Brand.Ink)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        if (user == null) {
            SignInScreen()
            return@Column
        }
        deletion?.let { DeletionBanner(it, session::keepAccount) }
        val reader = openReader?.split("|")
        if (reader != null) {
            BackHandler { openReader = null }
            ReaderScreen(
                sourceKey = reader[0],
                titleId = reader[1],
                chapterId = reader[2],
                startPage = reader[3].toInt(),
                onBack = { openReader = null },
                onOpenChapter = { chapter, page -> openReader = "${reader[0]}|${reader[1]}|$chapter|$page" },
                onWatch = { chapter, page ->
                    if (reader[0] == INTERNAL_SOURCE_KEY) {
                        // The episode from the page being read; closing it comes back to the reader where it was.
                        openPlayer = "$chapter|||$page"
                    } else {
                        // A source's chapter: its card first (its pages go to the server, then the cost, then Prepare).
                        openReader = null
                        openTitle = "${reader[0]}|${reader[1]}"
                        openEpisode = chapter
                    }
                },
                language = reader.getOrNull(4)?.ifEmpty { null },
                openLanguages = reader.getOrNull(5) == "pick",
                // A source's chapter translated: its copy on the server, in that language, at the same share.
                onOpenCopy = { title, chapter, language, page -> openReader = "$INTERNAL_SOURCE_KEY|$title|$chapter|$page|$language" },
            )
            return@Column
        }
        val adaptive = LocalAdaptive.current
        val select: (Tab) -> Unit = { entry ->
            tab = entry
            openTitle = null
            openEpisode = null
            openSource = null
            openDownloads = false
            openSettings = null
        }
        val isSelected: (Tab) -> Boolean = { entry -> entry == tab && openTitle == null && (entry != Tab.Sources || openSource == null) }
        Row(Modifier.weight(1f)) {
            // A tablet or an unfolded foldable: the tabs on the side (a rail, or a sidebar when wide).
            if (adaptive.navigation != Navigation.BottomBar) {
                SideTabs(adaptive.navigation == Navigation.Sidebar, isSelected, select)
            }
            Column(Modifier.weight(1f)) {
                val title = openTitle?.split("|")
                val source = openSource?.split("|", limit = 2)
                val episodeRoute = openEpisode?.split("|")
                val episode = episodeRoute?.first()
                if (title != null && episode != null) {
                    BackHandler { openEpisode = null }
                    // The title page's own ViewModel (same key): chapter label and the next chapter in reading order.
                    val detail: TitleDetailViewModel =
                        koinViewModel(key = "title:${title[0]}/${title[1]}") { parametersOf(title[0], title[1]) }
                    val titleState by detail.state.collectAsStateWithLifecycle()
                    EpisodeScreen(
                        chapterId = episode,
                        titleName = titleState.name,
                        chapterLabel = titleState.chapterLabel(episode),
                        onBack = { openEpisode = null },
                        // The player plays our chapter (a source's: its copy); "Next chapter →" comes back to this title.
                        onWatch = { played, language ->
                            openPlayer = "$played|${titleState.nextChapterId(episode).orEmpty()}|$language"
                        },
                        language = episodeRoute.getOrNull(1).orEmpty(),
                        sourceKey = title[0],
                        titleId = title[1],
                    )
                } else if (title != null) {
                    BackHandler { openTitle = null }
                    TitleScreen(
                        sourceKey = title[0],
                        titleId = title[1],
                        onBack = { openTitle = null },
                        onRead = { readSource, readTitle, chapter, page, language ->
                            openReader = "$readSource|$readTitle|$chapter|$page" + language?.let { "|$it" }.orEmpty()
                        },
                        onEpisode = { openEpisode = it },
                        onTranslate = { chapter -> openReader = "${title[0]}|${title[1]}|$chapter|0||pick" },
                        onOpenTitle = { source, id -> openTitle = "$source|$id" },
                    )
                } else if (tab == Tab.Library && openDownloads) {
                    BackHandler { openDownloads = false }
                    DownloadsScreen(onBack = { openDownloads = false }, onOpenTitle = { source, id -> openTitle = "$source|$id" })
                } else if (tab == Tab.Sources && source != null) {
                    BackHandler { openSource = null }
                    when (source[0]) {
                        "add-source" ->
                            AddSourceScreen(
                                repositoryId = source.getOrNull(1),
                                onBack = { openSource = null },
                                onOpenSource = { openSource = "source-detail|$it" },
                            )
                        else -> SourceDetailScreen(sourceKey = source[1], onBack = { openSource = null })
                    }
                } else {
                    when (tab) {
                        Tab.Catalog ->
                            CatalogScreen(
                                onOpenTitle = { source, id -> openTitle = "$source|$id" },
                                onOpenSources = { tab = Tab.Sources },
                                onResume = { source, id, chapter, page -> openReader = "$source|$id|$chapter|$page" },
                            )
                        Tab.Library ->
                            LibraryScreen(
                                onOpenTitle = { source, id -> openTitle = "$source|$id" },
                                onOpenDownloads = { openDownloads = true },
                                onOpenSources = { tab = Tab.Sources },
                                onOpenCatalog = { tab = Tab.Catalog },
                                onSignOut = session::signOut,
                            )
                        Tab.Sources ->
                            SourcesScreen(
                                onAdd = { repository -> openSource = if (repository == null) "add-source" else "add-source|$repository" },
                                onOpenSource = { openSource = "source-detail|$it" },
                            )
                        Tab.Settings ->
                            when (openSettings) {
                                "account" -> {
                                    BackHandler { openSettings = null }
                                    AccountScreen(onBack = { openSettings = null })
                                }
                                "preferences" -> {
                                    BackHandler { openSettings = null }
                                    PreferencesScreen(onBack = { openSettings = null }, onReplayTutorial = session::replayTutorial)
                                }
                                else ->
                                    SettingsScreen(
                                        onSignOut = session::signOut,
                                        accountDetail = user?.email.orEmpty(),
                                        onOpenAccount = { openSettings = "account" },
                                        onOpenPreferences = { openSettings = "preferences" },
                                    )
                            }
                    }
                }
            }
        }
        if (adaptive.navigation == Navigation.BottomBar) BottomTabs(isSelected, select)
    }
}

/** Phones: the tabs along the bottom, an icon over each label. */
@Composable
private fun BottomTabs(
    isSelected: (Tab) -> Boolean,
    onSelect: (Tab) -> Unit,
) {
    Row(Modifier.fillMaxWidth().background(Brand.Ink2)) {
        Tab.entries.forEach { entry ->
            val color = if (isSelected(entry)) Brand.Red else Brand.Muted
            Column(
                Modifier
                    .weight(1f)
                    .clickable { onSelect(entry) }
                    .padding(top = 10.dp, bottom = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Icon(entry.icon(), contentDescription = null, tint = color, modifier = Modifier.size(24.dp))
                Text(
                    entry.label,
                    style = MaterialTheme.typography.labelSmall.copy(color = color, fontSize = 11.sp, letterSpacing = 0.4.sp),
                )
            }
        }
    }
}

/**
 * Tablets and unfolded foldables: the same tabs down the start edge, a rail (icon over label) or, when the window is
 * wide, a sidebar (icon beside label). The selected tab carries a red bar on its edge.
 */
@Composable
private fun SideTabs(
    wide: Boolean,
    isSelected: (Tab) -> Boolean,
    onSelect: (Tab) -> Unit,
) {
    Column(
        Modifier
            .fillMaxHeight()
            .width(if (wide) 220.dp else 84.dp)
            .background(Brand.Ink2)
            .padding(top = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (wide) {
            Text(
                "vovoscan",
                style = MaterialTheme.typography.titleLarge.copy(color = Brand.Paper),
                modifier = Modifier.padding(start = 20.dp, bottom = 16.dp),
            )
        }
        Tab.entries.forEach { entry ->
            val selected = isSelected(entry)
            val color = if (selected) Brand.Red else Brand.Muted
            Box(
                Modifier
                    .fillMaxWidth()
                    .clickable(onClickLabel = entry.label) { onSelect(entry) }
                    .semantics { this.selected = selected },
            ) {
                if (selected) {
                    Box(
                        Modifier
                            .align(Alignment.CenterStart)
                            .width(3.dp)
                            .height(28.dp)
                            .background(Brand.Red),
                    )
                }
                if (wide) {
                    Row(
                        Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Icon(entry.icon(), contentDescription = null, tint = color, modifier = Modifier.size(24.dp))
                        Text(
                            entry.label,
                            style = MaterialTheme.typography.labelLarge.copy(color = if (selected) Brand.Paper else Brand.Muted),
                        )
                    }
                } else {
                    Column(
                        Modifier.fillMaxWidth().padding(vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Icon(entry.icon(), contentDescription = null, tint = color, modifier = Modifier.size(24.dp))
                        Text(
                            entry.label,
                            style = MaterialTheme.typography.labelSmall.copy(color = color, fontSize = 11.sp, letterSpacing = 0.4.sp),
                        )
                    }
                }
            }
        }
    }
}

/** While the account is scheduled for deletion: the date and "Keep my account", on every screen. */
@Composable
private fun DeletionBanner(
    banner: DeletionBannerUi,
    onKeep: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().background(Brand.Yellow).padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(banner.text, style = MaterialTheme.typography.bodySmall.copy(color = Brand.Ink), modifier = Modifier.weight(1f))
            Text(
                banner.action,
                style = MaterialTheme.typography.labelLarge.copy(color = if (banner.working) Brand.Ink.copy(alpha = 0.5f) else Brand.Ink),
                modifier =
                    Modifier
                        .clickable(
                            enabled = !banner.working,
                            onClick = onKeep,
                        ).padding(start = 12.dp, top = 4.dp, bottom = 4.dp),
            )
        }
        banner.error?.let { Text(it, style = MaterialTheme.typography.bodySmall.copy(color = Brand.Ink)) }
    }
}
