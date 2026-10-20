package com.vovoscan.app.ui.reader

import android.annotation.SuppressLint
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.vovoscan.app.ui.common.ErrorText
import com.vovoscan.app.ui.common.GhostButton
import com.vovoscan.app.ui.common.PrimaryButton
import com.vovoscan.app.ui.theme.Brand
import com.vovoscan.feature.reader.domain.ReaderPage
import com.vovoscan.feature.reader.domain.ReadingMode
import com.vovoscan.feature.reader.presentation.ReaderIntent
import com.vovoscan.feature.reader.presentation.ReaderUiState
import com.vovoscan.feature.reader.presentation.ReaderViewModel
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * Reader: continuous vertical strip (webtoon) or right-to-left pages (manga). Tapping the centre hides the
 * bars. The screen only displays: current page, resume and progress live in `ReaderViewModel`.
 */
@Composable
fun ReaderScreen(
    sourceKey: String,
    titleId: String,
    chapterId: String,
    startPage: Int,
    onBack: () -> Unit,
    onOpenChapter: (String) -> Unit,
    viewModel: ReaderViewModel =
        koinViewModel(key = "reader:$sourceKey/$chapterId") { parametersOf(sourceKey, titleId, chapterId, startPage) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Box(Modifier.fillMaxSize().background(Brand.Ink)) {
        when {
            state.unavailable ->
                Column(Modifier.align(Alignment.Center).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("This chapter isn't saved on this device yet.", style = MaterialTheme.typography.bodyMedium)
                    state.error?.let { ErrorText(it) }
                    GhostButton("Try again", { viewModel.onIntent(ReaderIntent.Retry) })
                }
            state.isBook -> BookReader(state, viewModel::onIntent, onOpenChapter)
            state.pages.isEmpty() -> Unit
            state.mode == ReadingMode.Vertical -> VerticalStrip(state, viewModel::onIntent, onOpenChapter)
            else -> RightToLeftPages(state, viewModel::onIntent)
        }
        // A book keeps its bars: the web view receives the taps (text scrolling).
        if (state.chromeVisible || state.isBook) {
            TopBar(state, onBack, Modifier.align(Alignment.TopCenter))
            BottomBar(state, viewModel::onIntent, onOpenChapter, Modifier.align(Alignment.BottomCenter))
        }
    }
}

@Composable
private fun VerticalStrip(
    state: ReaderUiState,
    onIntent: (ReaderIntent) -> Unit,
    onOpenChapter: (String) -> Unit,
) {
    val list = rememberLazyListState()
    var positioned by remember { mutableStateOf(false) }
    // Resume: scroll to the saved page once, as soon as the pages are available.
    LaunchedEffect(state.pages.size) {
        if (!positioned && state.pages.isNotEmpty()) {
            list.scrollToItem(state.currentIndex)
            positioned = true
        }
    }
    LaunchedEffect(list) { snapshotFlow { list.firstVisibleItemIndex }.collect { onIntent(ReaderIntent.PageVisible(it)) } }
    LazyColumn(
        state = list,
        modifier = Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { onIntent(ReaderIntent.ToggleChrome) } },
    ) {
        itemsIndexed(state.pages, key = { _, page -> page.index }) { _, page -> PageImage(page, Modifier.fillMaxWidth()) }
        item {
            Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("End of ${state.chapterLabel}.", style = MaterialTheme.typography.titleMedium)
                state.nextChapterId?.let { next -> PrimaryButton("Next chapter →", { onOpenChapter(next) }) }
                Spacer(Modifier.height(64.dp))
            }
        }
    }
}

@Composable
private fun RightToLeftPages(
    state: ReaderUiState,
    onIntent: (ReaderIntent) -> Unit,
) {
    val pager = rememberPagerState(initialPage = state.currentIndex) { state.pages.size }
    LaunchedEffect(pager) { snapshotFlow { pager.settledPage }.collect { onIntent(ReaderIntent.PageVisible(it)) } }
    // reverseLayout: the first page is on the right, swiping right moves forward (manga direction).
    HorizontalPager(
        state = pager,
        reverseLayout = true,
        key = { state.pages[it].index },
        modifier = Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { onIntent(ReaderIntent.ToggleChrome) } },
    ) { index ->
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            PageImage(state.pages[index], Modifier.fillMaxWidth(), ContentScale.Fit)
        }
    }
}

@Composable
private fun PageImage(
    page: ReaderPage,
    modifier: Modifier,
    scale: ContentScale = ContentScale.FillWidth,
) {
    // Space is reserved from the known dimensions: no jump when the image arrives.
    AsyncImage(
        model = page.url,
        contentDescription = "Page ${page.index + 1}",
        contentScale = scale,
        modifier = modifier.aspectRatio(page.aspectRatio).background(Brand.Ink2),
    )
}

@Composable
private fun TopBar(
    state: ReaderUiState,
    onBack: () -> Unit,
    modifier: Modifier,
) {
    Row(
        modifier.fillMaxWidth().background(Brand.Ink.copy(alpha = 0.92f)).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("←", style = MaterialTheme.typography.titleMedium, modifier = Modifier.clickable(onClick = onBack).padding(end = 16.dp))
        Column(Modifier.weight(1f)) {
            Text(state.titleName, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(state.chapterLabel, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun BottomBar(
    state: ReaderUiState,
    onIntent: (ReaderIntent) -> Unit,
    onOpenChapter: (String) -> Unit,
    modifier: Modifier,
) {
    Row(
        modifier.fillMaxWidth().background(Brand.Ink.copy(alpha = 0.92f)).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (state.isBook) {
            Text(
                "←",
                style = MaterialTheme.typography.titleMedium.copy(color = if (state.canGoBack) Brand.Paper else Brand.Line),
                modifier = Modifier.clickable(enabled = state.canGoBack) { onIntent(ReaderIntent.Previous) }.padding(end = 16.dp),
            )
        }
        Text(state.positionLabel, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        if (state.isBook && state.canGoForward) {
            Text(
                "Next section →",
                style = MaterialTheme.typography.labelLarge.copy(color = Brand.Red),
                modifier = Modifier.clickable { onIntent(ReaderIntent.Next) },
            )
            return@Row
        }
        state.previousChapterId?.let { previous ->
            Text("← Previous", style = MaterialTheme.typography.bodySmall, modifier = Modifier.clickable { onOpenChapter(previous) })
            Spacer(Modifier.width(20.dp))
        }
        state.nextChapterId?.let { next ->
            Text(
                "Next →",
                style = MaterialTheme.typography.labelLarge.copy(color = Brand.Red),
                modifier = Modifier.clickable { onOpenChapter(next) },
            )
        }
    }
}

/**
 * Book: one section at a time, in a web view with JavaScript off, no network and no navigation. The HTML is
 * already sanitised and styled (BookStyle); images are embedded.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun BookReader(
    state: ReaderUiState,
    onIntent: (ReaderIntent) -> Unit,
    onOpenChapter: (String) -> Unit,
) {
    val document = state.sectionDocument
    Box(Modifier.fillMaxSize().padding(top = 64.dp, bottom = 56.dp)) {
        when {
            state.preparingBook || document == null ->
                Text(
                    if (state.preparingBook) "Opening the book…" else "",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.align(Alignment.Center),
                )
            else ->
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { context ->
                        WebView(context).apply {
                            setBackgroundColor(android.graphics.Color.parseColor("#0B0B0E"))
                            settings.javaScriptEnabled = false
                            settings.blockNetworkLoads = true
                            settings.allowFileAccess = false
                            settings.allowContentAccess = false
                            // No link leaves the book.
                            webViewClient =
                                object : WebViewClient() {
                                    override fun shouldOverrideUrlLoading(
                                        view: WebView,
                                        request: WebResourceRequest,
                                    ) = true
                                }
                        }
                    },
                    update = { view ->
                        if (view.tag != document) {
                            view.tag = document
                            view.loadDataWithBaseURL(null, document, "text/html", "utf-8", null)
                            view.scrollTo(0, 0)
                        }
                    },
                )
        }
        if (state.atEnd && state.nextChapterId != null && document != null) {
            Box(Modifier.align(Alignment.BottomCenter).padding(16.dp)) {
                PrimaryButton(state.nextLabel ?: "Next →", { onOpenChapter(state.nextChapterId!!) })
            }
        }
    }
}
