package com.vovoscan.app.ui.title

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.vovoscan.app.ui.common.ErrorText
import com.vovoscan.app.ui.common.GhostButton
import com.vovoscan.app.ui.common.PrimaryButton
import com.vovoscan.app.ui.theme.Brand
import com.vovoscan.feature.catalog.presentation.TitleDetailViewModel
import com.vovoscan.feature.library.presentation.FollowViewModel
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/** Title page. Two features share it, unaware of each other: details (catalog) and following (library). */
@Composable
fun TitleScreen(
    sourceKey: String,
    titleId: String,
    onBack: () -> Unit,
    onRead: (chapterId: String, startPage: Int) -> Unit,
    onEpisode: (chapterId: String) -> Unit,
    detail: TitleDetailViewModel = koinViewModel(key = "title:$sourceKey/$titleId") { parametersOf(sourceKey, titleId) },
    follow: FollowViewModel = koinViewModel(key = "follow:$sourceKey/$titleId") { parametersOf(sourceKey, titleId) },
) {
    val state by detail.state.collectAsStateWithLifecycle()
    val followState by follow.state.collectAsStateWithLifecycle()
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            AsyncImage(
                state.coverUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().height(260.dp).background(Brand.Ink2),
            )
        }
        item {
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                GhostButton("← Back", onBack)
                if (state.unavailable) {
                    Text("This title isn't saved on this device yet.", style = MaterialTheme.typography.bodyMedium)
                }
                state.error?.let { ErrorText(it) }
                Text(state.name, style = MaterialTheme.typography.headlineSmall)
                Text(state.meta, style = MaterialTheme.typography.bodySmall)
                // Resume where the reader left off, otherwise start at the first chapter (the list runs newest to oldest).
                val resume = followState.resumeChapterId
                when {
                    resume != null ->
                        PrimaryButton(followState.resumeLabel(state.isBook) ?: "Continue →", { onRead(resume, followState.resumePage) })
                    state.chapters.isNotEmpty() ->
                        PrimaryButton(if (state.isBook) "Read →" else "Start reading →", { onRead(state.chapters.last().id, 0) })
                }
                if (state.name.isNotEmpty()) GhostButton(followState.label, { follow.toggle(state.name, state.coverUrl) })
                Text(state.synopsis, style = MaterialTheme.typography.bodyMedium)
                if (state.genres.isNotEmpty()) Text(state.genres.joinToString(" ✦ "), style = MaterialTheme.typography.bodySmall)
                if (state.chapters.isNotEmpty()) {
                    Text(
                        if (state.isBook) "Contents" else "Chapters",
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
        }
        items(state.chapters, key = { it.id }) { chapter ->
            Row(
                Modifier
                    .padding(horizontal = 20.dp)
                    .fillMaxWidth()
                    .background(Brand.Ink2, RoundedCornerShape(12.dp))
                    .border(1.dp, Brand.Line, RoundedCornerShape(12.dp))
                    .clickable { onRead(chapter.id, 0) }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(chapter.label, style = MaterialTheme.typography.bodyMedium)
                    Text(chapter.meta, style = MaterialTheme.typography.bodySmall)
                }
                // A comic of our catalog: the chapter's voiced episode, next to reading it.
                if (state.offersEpisodes) {
                    Text(
                        "Episode",
                        style = MaterialTheme.typography.labelSmall.copy(color = Brand.Paper),
                        modifier =
                            Modifier
                                .border(1.dp, Brand.Line, RoundedCornerShape(999.dp))
                                .clickable { onEpisode(chapter.id) }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}
