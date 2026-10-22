package com.vovoscan.app

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vovoscan.app.ui.catalog.CatalogScreen
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
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

class MainActivity : ComponentActivity() {
    /** Set by the reader while it is on screen: true when it used the volume key (up = volume up). */
    var volumeKeys: ((up: Boolean) -> Boolean)? = null
    private var volumeKeyTaken = false

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
    }
}

private enum class Tab(
    val label: String,
) {
    Catalog("Catalog"),
    Library("Library"),
    Sources("Sources"),
    Settings("Settings"),
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
                    Tab.Library -> LibraryScreen(onOpenTitle = { source, id -> openTitle = "$source|$id" }, onSignOut = session::signOut)
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
                Text(
                    entry.label,
                    style = MaterialTheme.typography.labelLarge.copy(color = if (selected) Brand.Red else Brand.Muted),
                    textAlign = TextAlign.Center,
                    modifier =
                        Modifier
                            .weight(1f)
                            .clickable {
                                tab = entry
                                openTitle = null
                                openEpisode = null
                                openSource = null
                            }.padding(vertical = 16.dp),
                )
            }
        }
    }
}
