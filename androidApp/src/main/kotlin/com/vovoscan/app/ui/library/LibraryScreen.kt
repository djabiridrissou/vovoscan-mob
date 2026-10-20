package com.vovoscan.app.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.vovoscan.app.ui.common.ErrorText
import com.vovoscan.app.ui.common.GhostButton
import com.vovoscan.app.ui.common.Kicker
import com.vovoscan.app.ui.common.OfflineNotice
import com.vovoscan.app.ui.theme.Brand
import com.vovoscan.feature.library.presentation.LibraryIntent
import com.vovoscan.feature.library.presentation.LibraryViewModel
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun LibraryScreen(
    onOpenTitle: (sourceKey: String, titleId: String) -> Unit,
    onSignOut: () -> Unit,
    viewModel: LibraryViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Kicker("Library")
                Text("Keep reading.", style = MaterialTheme.typography.headlineSmall)
                state.pendingNotice?.let { OfflineNotice(it) }
                state.error?.let { ErrorText(it) }
                if (state.empty) Text(state.emptyMessage, style = MaterialTheme.typography.bodyMedium)
            }
        }
        items(state.rows, key = { it.sourceKey + "/" + it.titleId }) { row ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Brand.Ink2)
                    .border(1.dp, Brand.Line, RoundedCornerShape(12.dp))
                    .clickable { onOpenTitle(row.sourceKey, row.titleId) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AsyncImage(row.coverUrl, null, contentScale = ContentScale.Crop, modifier = Modifier.size(64.dp).background(Brand.Line))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(row.name, style = MaterialTheme.typography.bodyMedium)
                    Text(row.progress, style = MaterialTheme.typography.bodySmall)
                }
                // Cyan: action waiting to be sent (the brand reserves cyan for work in progress).
                if (row.pendingSync) Box(Modifier.size(10.dp).background(Brand.Cyan, CircleShape))
                Spacer(Modifier.width(14.dp))
            }
        }
        item { GhostButton("Sync now", { viewModel.onIntent(LibraryIntent.Sync) }) }
        item { GhostButton("Sign out", onSignOut) }
    }
}
