package com.vovoscan.app.ui.downloads

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.vovoscan.app.ui.common.AppIcons
import com.vovoscan.app.ui.theme.Brand
import com.vovoscan.feature.reader.presentation.DownloadAction
import com.vovoscan.feature.reader.presentation.DownloadRow
import com.vovoscan.feature.reader.presentation.DownloadStatus

/**
 * A chapter's download control, from its row: download (arrow), progress ring with a stop square (queued, waiting,
 * downloading: cyan, work in progress), downloaded (check), failed (retry). Nothing for a book not opened yet.
 */
@Composable
fun DownloadControl(
    row: DownloadRow,
    onClick: () -> Unit,
) {
    if (row.action == DownloadAction.Unavailable) return
    Box(
        Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        when (row.status) {
            DownloadStatus.NotDownloaded ->
                Icon(
                    AppIcons.Download,
                    contentDescription = "Download",
                    tint = Brand.Muted,
                    modifier = Modifier.size(26.dp),
                )
            DownloadStatus.Done ->
                Icon(
                    AppIcons.Downloaded,
                    contentDescription = "Downloaded. Delete",
                    tint = Brand.Paper,
                    modifier = Modifier.size(26.dp),
                )
            DownloadStatus.Failed ->
                Icon(
                    AppIcons.Failed,
                    contentDescription = "Failed. Try again",
                    tint = Brand.Danger,
                    modifier = Modifier.size(26.dp),
                )
            else -> {
                CircularProgressIndicator(
                    progress = { if (row.status == DownloadStatus.Downloading) row.progress else 0f },
                    color = Brand.Cyan,
                    trackColor = Brand.Line,
                    strokeWidth = 2.5.dp,
                    modifier = Modifier.size(26.dp),
                )
                Icon(AppIcons.Stop, contentDescription = "Stop the download", tint = Brand.Paper, modifier = Modifier.size(26.dp))
            }
        }
    }
}
