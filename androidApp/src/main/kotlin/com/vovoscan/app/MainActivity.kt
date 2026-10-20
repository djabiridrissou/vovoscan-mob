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
}

/** Racine : connexion, ou onglets Catalogue / Bibliothèque, avec la fiche d'un titre par-dessus. */
@Composable
private fun Root(session: SessionViewModel = koinViewModel()) {
    val user by session.session.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(Tab.Catalog) }
    var openTitle by rememberSaveable { mutableStateOf<String?>(null) }
    // Lecteur ouvert : « chapitre|page de départ ».
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
                chapterId = reader[0],
                startPage = reader[1].toInt(),
                onBack = { openReader = null },
                onOpenChapter = { openReader = "$it|0" },
            )
            return@Column
        }
        Column(Modifier.weight(1f)) {
            val titleId = openTitle
            if (titleId != null) {
                BackHandler { openTitle = null }
                TitleScreen(titleId, onBack = { openTitle = null }, onRead = { chapter, page -> openReader = "$chapter|$page" })
            } else {
                when (tab) {
                    Tab.Catalog -> CatalogScreen(onOpenTitle = { openTitle = it })
                    Tab.Library -> LibraryScreen(onOpenTitle = { openTitle = it }, onSignOut = session::signOut)
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
