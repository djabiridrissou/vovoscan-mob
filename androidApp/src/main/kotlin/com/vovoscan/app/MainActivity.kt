package com.vovoscan.app

import android.os.Bundle
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vovoscan.app.ui.catalog.CatalogScreen
import com.vovoscan.app.ui.library.LibraryScreen
import com.vovoscan.app.ui.reader.ReaderScreen
import com.vovoscan.app.ui.signin.SignInScreen
import com.vovoscan.app.ui.sources.SourcesScreen
import com.vovoscan.app.ui.theme.Brand
import com.vovoscan.app.ui.theme.VovoscanTheme
import com.vovoscan.app.ui.title.TitleScreen
import com.vovoscan.feature.auth.presentation.SessionViewModel
import org.koin.compose.viewmodel.koinViewModel

class MainActivity : ComponentActivity() {
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
}

/** Racine : connexion, ou onglets Catalogue / Bibliothèque / Sources, avec la fiche d'un titre et le lecteur par-dessus. */
@Composable
private fun Root(session: SessionViewModel = koinViewModel()) {
    val user by session.session.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(Tab.Catalog) }
    // Fiche ouverte : « source|titre ».
    var openTitle by rememberSaveable { mutableStateOf<String?>(null) }
    // Lecteur ouvert : « source|titre|chapitre|page de départ ».
    var openReader by rememberSaveable { mutableStateOf<String?>(null) }
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
                onOpenChapter = { openReader = "${reader[0]}|${reader[1]}|$it|0" },
            )
            return@Column
        }
        Column(Modifier.weight(1f)) {
            val title = openTitle?.split("|")
            if (title != null) {
                BackHandler { openTitle = null }
                TitleScreen(
                    sourceKey = title[0],
                    titleId = title[1],
                    onBack = { openTitle = null },
                    onRead = { chapter, page -> openReader = "${title[0]}|${title[1]}|$chapter|$page" },
                )
            } else {
                when (tab) {
                    Tab.Catalog -> CatalogScreen(onOpenTitle = { source, id -> openTitle = "$source|$id" })
                    Tab.Library -> LibraryScreen(onOpenTitle = { source, id -> openTitle = "$source|$id" }, onSignOut = session::signOut)
                    Tab.Sources -> SourcesScreen()
                }
            }
        }
        Row(Modifier.fillMaxWidth().background(Brand.Ink2)) {
            Tab.entries.forEach { entry ->
                val selected = entry == tab && openTitle == null
                Text(
                    entry.label,
                    style = MaterialTheme.typography.labelLarge.copy(color = if (selected) Brand.Red else Brand.Muted),
                    modifier =
                        Modifier
                            .weight(1f)
                            .clickable {
                                tab = entry
                                openTitle = null
                            }.padding(vertical = 16.dp)
                            .padding(start = 24.dp),
                )
            }
        }
    }
}
