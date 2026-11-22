package com.example.bookreader.ui.reader

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.bookreader.domain.model.PageTurnHand
import com.example.bookreader.domain.model.ReadingMode
import com.example.bookreader.parser.PdfBookRenderer
import kotlinx.coroutines.launch
import java.io.File

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun PdfReaderView(
    pdfFile: File,
    isTablet: Boolean,
    initialPage: Int,
    readingMode: ReadingMode,
    pageTurnHand: PageTurnHand,
    showPageBadge: Boolean,
    onProgressChanged: (progressPercent: Float, currentPage: Int, totalPages: Int) -> Unit,
    onToggleControls: () -> Unit,
    onBottomEdgeTap: () -> Unit,
    onPageTurn: () -> Unit,
    modifier: Modifier = Modifier
) {
    val renderer = remember(pdfFile) { PdfBookRenderer(pdfFile) }
    var isInitialized by remember { mutableStateOf(false) }

    DisposableEffect(renderer) {
        onDispose {
            renderer.close()
        }
    }

    LaunchedEffect(renderer) {
        isInitialized = renderer.init()
    }

    if (!isInitialized) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    val totalPages = renderer.pageCount
    if (totalPages == 0) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("PDF 内容为空或无法解析")
        }
        return
    }

    val pageMode = readingMode == ReadingMode.PAGE
    val isTwoPageMode = isTablet && totalPages > 1 && !pageMode
    val pagerItemCount = if (isTwoPageMode) (totalPages + 1) / 2 else totalPages
    val startPagerIndex = if (isTwoPageMode) (initialPage - 1).coerceAtLeast(0) / 2 else (initialPage - 1).coerceIn(0, totalPages - 1)

    val pagerState = rememberPagerState(initialPage = startPagerIndex, pageCount = { pagerItemCount })
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(pagerState.currentPage) {
        val currentPage = if (isTwoPageMode) (pagerState.currentPage * 2) + 1 else pagerState.currentPage + 1
        val progress = currentPage.toFloat() / totalPages.toFloat()
        onProgressChanged(progress, currentPage, totalPages)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF2E2E2E))
            .pointerInput(pageMode, pageTurnHand) {
                detectTapGestures(
                    onTap = { offset ->
                        val width = size.width.toFloat()
                        val height = size.height
                        if (offset.y >= height * 0.82f) {
                            onBottomEdgeTap()
                        } else if (!pageMode || pageTurnHand.movesForward(offset.x, width) == null) {
                            if (offset.x >= width * 0.35f && offset.x < width * 0.65f) {
                                onToggleControls()
                            }
                        } else if (pageTurnHand.movesForward(offset.x, width) == true) {
                            onPageTurn()
                            coroutineScope.launch {
                                val target = (pagerState.currentPage + 1).coerceAtMost(pagerState.pageCount - 1)
                                if (target != pagerState.currentPage) {
                                    pagerState.animateScrollToPage(target)
                                }
                            }
                        } else {
                            onPageTurn()
                            coroutineScope.launch {
                                val target = (pagerState.currentPage - 1).coerceAtLeast(0)
                                if (target != pagerState.currentPage) {
                                    pagerState.animateScrollToPage(target)
                                }
                            }
                        }
                    }
                )
            }
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize()
        ) { pageIndex ->
            if (isTwoPageMode) {
                // 平板双页并排展开
                val leftPageIndex = pageIndex * 2
                val rightPageIndex = leftPageIndex + 1

                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    PdfSinglePageView(
                        renderer = renderer,
                        pageIndex = leftPageIndex,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    if (rightPageIndex < totalPages) {
                        PdfSinglePageView(
                            renderer = renderer,
                            pageIndex = rightPageIndex,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                        )
                    } else {
                        // 最后一页单独占位
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            } else {
                // 手机单页
                PdfSinglePageView(
                    renderer = renderer,
                    pageIndex = pageIndex,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(8.dp)
                )
            }
        }

        if (showPageBadge) {
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 12.dp)
                .background(Color.Black.copy(alpha = 0.5f), shape = MaterialTheme.shapes.small)
                .padding(horizontal = 10.dp, vertical = 4.dp)
        ) {
            val displayPageText = if (isTwoPageMode) {
                val left = (pagerState.currentPage * 2) + 1
                val right = minOf(left + 1, totalPages)
                if (left == right) "$left / $totalPages" else "$left-$right / $totalPages"
            } else {
                "${pagerState.currentPage + 1} / $totalPages"
            }
            Text(
                text = displayPageText,
                color = Color.White,
                fontSize = 12.sp
            )
        }
        }
    }
}

@Composable
private fun PdfSinglePageView(
    renderer: PdfBookRenderer,
    pageIndex: Int,
    modifier: Modifier = Modifier
) {
    var bitmap by remember(pageIndex) { mutableStateOf<Bitmap?>(null) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    val transformState = rememberTransformableState { zoomChange, offsetChange, _ ->
        scale = (scale * zoomChange).coerceIn(1f, 3.5f)
        if (scale > 1f) {
            offset += offsetChange
        } else {
            offset = Offset.Zero
        }
    }

    LaunchedEffect(pageIndex) {
        bitmap = renderer.renderPage(pageIndex, targetWidth = 1400)
    }

    Box(
        modifier = modifier
            .transformable(state = transformState)
            .graphicsLayer(
                scaleX = scale,
                scaleY = scale,
                translationX = offset.x,
                translationY = offset.y
            ),
        contentAlignment = Alignment.Center
    ) {
        val currentBitmap = bitmap
        if (currentBitmap != null) {
            Image(
                bitmap = currentBitmap.asImageBitmap(),
                contentDescription = "PDF Page $pageIndex",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            CircularProgressIndicator(color = Color.White)
        }
    }
}
