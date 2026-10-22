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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.vovoscan.app.ui.catalog.CatalogScreen
import com.vovoscan.app.ui.common.AppIcons
import com.vovoscan.app.ui.downloads.DownloadsScreen
import com.vovoscan.app.ui.episode.EpisodeScreen
import com.vovoscan.app.ui.library.LibraryScreen
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
import com.vovoscan.feature.auth.presentation.SessionViewModel
import com.vovoscan.feature.catalog.presentation.TitleDetailViewModel
import com.vovoscan.feature.reader.domain.DownloadsRepository
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
                Root()
            }
        }
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
    val label: String,
    val icon: () -> ImageVector,
) {
    Catalog("Catalog", { AppIcons.Catalog }),
    Library("Library", { AppIcons.Library }),
    Sources("Sources", { AppIcons.Sources }),
    Settings("Settings", { AppIcons.Settings }),
}

/**
 * Root: sign-in, or the Catalog / Library / Sources / Settings tabs, with the title page, the source screens, the
 * reader, an episode card and the full-screen episode player on top.
 */
@Composable
private fun Root(session: SessionViewModel = koinViewModel()) {
    val user by session.session.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(Tab.Catalog) }
    // Open title page: "source|title".
    var openTitle by rememberSaveable { mutableStateOf<String?>(null) }
    // Downloads screen open (Library tab).
    var openDownloads by rememberSaveable { mutableStateOf(false) }
    // Open sources screen (Sources tab): "add-source", "add-source|repository id" or "source-detail|source key".
    var openSource by rememberSaveable { mutableStateOf<String?>(null) }
    // Open reader: "source|title|chapter|start page" (`ReaderViewModel.START_AT_END`: from its last page).
    var openReader by rememberSaveable { mutableStateOf<String?>(null) }
    // Open episode card (over the title page): chapter id.
    var openEpisode by rememberSaveable { mutableStateOf<String?>(null) }
    // Open player (full screen): "chapter|next chapter or empty".
    var openPlayer by rememberSaveable { mutableStateOf<String?>(null) }
    val player = openPlayer?.split("|")
    if (user != null && player != null) {
        BackHandler { openPlayer = null }
        PlayerScreen(
            chapterId = player[0],
            nextChapterId = player[1].ifEmpty { null },
            onClose = { openPlayer = null },
            onNextChapter = {
                // The next chapter's episode card: watch it from there, or prepare it.
                openPlayer = null
                openEpisode = it
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
            )
            return@Column
        }
        Column(Modifier.weight(1f)) {
            val title = openTitle?.split("|")
            val source = openSource?.split("|", limit = 2)
            val episode = openEpisode
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
                    onWatch = { openPlayer = "$episode|${titleState.nextChapterId(episode).orEmpty()}" },
                )
            } else if (title != null) {
                BackHandler { openTitle = null }
                TitleScreen(
                    sourceKey = title[0],
                    titleId = title[1],
                    onBack = { openTitle = null },
                    onRead = { chapter, page -> openReader = "${title[0]}|${title[1]}|$chapter|$page" },
                    onEpisode = { openEpisode = it },
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
                    Tab.Catalog -> CatalogScreen(onOpenTitle = { source, id -> openTitle = "$source|$id" })
                    Tab.Library ->
                        LibraryScreen(
                            onOpenTitle = { source, id -> openTitle = "$source|$id" },
                            onOpenDownloads = { openDownloads = true },
                            onSignOut = session::signOut,
                        )
                    Tab.Sources ->
                        SourcesScreen(
                            onAdd = { repository -> openSource = if (repository == null) "add-source" else "add-source|$repository" },
                            onOpenSource = { openSource = "source-detail|$it" },
                        )
                    Tab.Settings -> SettingsScreen(onSignOut = session::signOut)
                }
            }
        }
        Row(Modifier.fillMaxWidth().background(Brand.Ink2)) {
            Tab.entries.forEach { entry ->
                val selected = entry == tab && openTitle == null && (entry != Tab.Sources || openSource == null)
                val color = if (selected) Brand.Red else Brand.Muted
                Column(
                    Modifier
                        .weight(1f)
                        .clickable {
                            tab = entry
                            openTitle = null
                            openEpisode = null
                            openSource = null
                            openDownloads = false
                        }.padding(top = 10.dp, bottom = 8.dp),
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
