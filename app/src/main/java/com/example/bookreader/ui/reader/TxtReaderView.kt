package com.example.bookreader.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.bookreader.domain.model.ReadingMode
import com.example.bookreader.domain.model.ReaderSettings
import com.example.bookreader.parser.TxtParsedResult
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.roundToInt

@Composable
fun TxtReaderView(
    parsedResult: TxtParsedResult,
    settings: ReaderSettings,
    isTablet: Boolean,
    currentChapterIndex: Int,
    initialCharOffset: Long,
    reserveBottomBar: Boolean,
    onProgressChanged: (progressPercent: Float, charOffset: Long, chapterIndex: Int, chapterFraction: Float, chapterPageIndex: Int, chapterPageCount: Int) -> Unit,
    onToggleControls: () -> Unit,
    onBottomEdgeTap: () -> Unit,
    onPageTurn: () -> Unit,
    onNextChapter: () -> Unit,
    onPreviousChapter: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (settings.readingMode == ReadingMode.PAGE) {
        TxtPageReader(
            parsedResult = parsedResult,
            settings = settings,
            currentChapterIndex = currentChapterIndex,
            initialCharOffset = initialCharOffset,
            reserveBottomBar = reserveBottomBar,
            onProgressChanged = onProgressChanged,
            onToggleControls = onToggleControls,
            onBottomEdgeTap = onBottomEdgeTap,
            onPageTurn = onPageTurn,
            onNextChapter = onNextChapter,
            onPreviousChapter = onPreviousChapter,
            modifier = modifier
        )
        return
    }

    val bgColor = Color(settings.bgTheme.bgHex)
    val textColor = Color(settings.bgTheme.textHex)

    // 在平板横屏下，将长文本自然拆分为左右双栏；在手机上单栏展示
    val enableTwoColumn = isTablet && settings.twoPageModeForTablet
    var viewportHeightPx by remember { mutableIntStateOf(0) }

    val scrollState = rememberScrollState()
    val coroutineScope = rememberCoroutineScope()

    // 初始位置恢复：等待排版计算出内容真实高度后准确还原
    var hasRestored by remember { mutableStateOf(false) }
    LaunchedEffect(scrollState.maxValue, parsedResult) {
        if (!hasRestored && scrollState.maxValue > 0 && parsedResult.totalLength > 0 && initialCharOffset > 0) {
            val fraction = (initialCharOffset.toFloat() / parsedResult.totalLength.toFloat()).coerceIn(0f, 1f)
            scrollState.scrollTo((scrollState.maxValue * fraction).toInt())
            hasRestored = true
        }
    }

    // 目录章节切换监听
    LaunchedEffect(currentChapterIndex) {
        if (hasRestored && scrollState.maxValue > 0 && parsedResult.totalLength > 0) {
            val targetOffset = parsedResult.chapters.getOrNull(currentChapterIndex)?.startCharOffset ?: 0L
            val fraction = (targetOffset.toFloat() / parsedResult.totalLength.toFloat()).coerceIn(0f, 1f)
            scrollState.animateScrollTo((scrollState.maxValue * fraction).toInt())
        }
    }

    // 监听滚动更新进度
    LaunchedEffect(scrollState, viewportHeightPx) {
        snapshotFlow {
            val percent = if (scrollState.maxValue > 0) {
                scrollState.value.toFloat() / scrollState.maxValue.toFloat()
            } else {
                0f
            }
            Triple(percent, viewportHeightPx, scrollState.maxValue)
        }.distinctUntilChanged().collect { (percent, viewportPx, maxScroll) ->
            val charOffset = (percent * parsedResult.totalLength).toLong()
            val chapterIdx = parsedResult.chapters.indexOfLast { it.startCharOffset <= charOffset }.coerceAtLeast(0)
            val chapterStart = parsedResult.chapters.getOrNull(chapterIdx)?.startCharOffset ?: 0L
            val chapterEnd = parsedResult.chapters.getOrNull(chapterIdx + 1)?.startCharOffset ?: parsedResult.totalLength
            val span = (chapterEnd - chapterStart).coerceAtLeast(1L)
            val chapterFraction = ((charOffset - chapterStart).toFloat() / span.toFloat()).coerceIn(0f, 1f)
            val viewport = viewportPx.coerceAtLeast(1)
            val bookPages = ((maxScroll + viewport).toFloat() / viewport.toFloat()).toInt().coerceAtLeast(1)
            val chapterShare = span.toFloat() / parsedResult.totalLength.coerceAtLeast(1L).toFloat()
            val chapterPages = (bookPages * chapterShare).toInt().coerceAtLeast(1)
            val pageIndex = (chapterFraction * (chapterPages - 1)).toInt().coerceIn(0, chapterPages - 1)
            onProgressChanged(percent, charOffset, chapterIdx, chapterFraction, pageIndex, chapterPages)
        }
    }

    val density = LocalDensity.current
    val lineHeightPx = with(density) {
        (settings.fontSizeSp * settings.lineSpacingMultiplier).sp.toPx()
    }.coerceAtLeast(1f)
    val verticalPadPx = with(density) { 16.dp.roundToPx() }
    val scrollTextStyle = LocalTextStyle.current.merge(
        TextStyle(
            fontSize = settings.fontSizeSp.sp,
            lineHeight = (settings.fontSizeSp * settings.lineSpacingMultiplier).sp,
            letterSpacing = 0.sp,
            color = textColor,
            platformStyle = PlatformTextStyle(includeFontPadding = false),
            lineHeightStyle = LineHeightStyle(
                alignment = LineHeightStyle.Alignment.Center,
                trim = LineHeightStyle.Trim.None
            )
        )
    )
    var textLayout by remember { mutableStateOf<TextLayoutResult?>(null) }

    val scrollTop = scrollState.value
    val viewportPx = scrollState.viewportSize.takeIf { it > 0 } ?: (viewportHeightPx - verticalPadPx * 2).coerceAtLeast(1)
    val bottomPadPx = 6
    val visibleBottom = scrollTop + viewportPx - bottomPadPx
    val layout = textLayout
    var nextPageSeam = scrollTop + ((viewportPx / lineHeightPx).toInt().coerceAtLeast(1) * lineHeightPx).roundToInt()
    var bottomMaskPx = 0
    if (layout != null && layout.lineCount > 0 && !enableTwoColumn) {
        val lastContentBottom = layout.getLineBottom(layout.lineCount - 1)
        if (lastContentBottom > visibleBottom + 1f) {
            var lastCurr = -1
            var firstNext = -1
            for (i in 0 until layout.lineCount) {
                val b = layout.getLineBottom(i)
                val t = layout.getLineTop(i)
                if (b <= scrollTop + 2f) continue
                if (b <= visibleBottom) {
                    lastCurr = i
                } else if (t > scrollTop + 2f) {
                    firstNext = i
                    break
                } else {
                    lastCurr = i
                }
            }
            if (firstNext != -1) {
                val seam = if (lastCurr != -1) {
                    ((layout.getLineBottom(lastCurr) + layout.getLineTop(firstNext)) * 0.5f).roundToInt()
                } else {
                    layout.getLineTop(firstNext).roundToInt()
                }
                nextPageSeam = seam.coerceAtLeast(scrollTop + 4)
                if (seam < scrollTop + viewportPx) {
                    bottomMaskPx = ((scrollTop + viewportPx - seam) + verticalPadPx).coerceAtLeast(0)
                }
            }
        }
    }
    val nextPageSeamState = rememberUpdatedState(nextPageSeam)
    val textLayoutState = rememberUpdatedState(textLayout)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(bgColor)
            .onSizeChanged { viewportHeightPx = it.height }
            .pointerInput(scrollState, settings.pageTurnHand, lineHeightPx) {
                detectTapGestures(
                    onTap = { offset ->
                        val width = size.width.toFloat()
                        val height = size.height
                        val viewport = scrollState.viewportSize.takeIf { it > 0 } ?: (height - verticalPadPx * 2).coerceAtLeast(1)
                        val visibleLines = (viewport / lineHeightPx).toInt().coerceAtLeast(1)
                        val fallbackStepPx = (visibleLines * lineHeightPx).toInt().coerceAtLeast(1)
                        if (offset.y >= height * 0.82f) {
                            onBottomEdgeTap()
                        } else {
                            when (settings.pageTurnHand.movesForward(offset.x, width)) {
                                true -> {
                                    onPageTurn()
                                    val currLayout = textLayoutState.value
                                    val contentMax = if (currLayout != null && currLayout.lineCount > 0) {
                                        currLayout.getLineBottom(currLayout.lineCount - 1).roundToInt()
                                    } else {
                                        scrollState.maxValue
                                    }
                                    if (scrollState.maxValue > 0 && (scrollState.value >= scrollState.maxValue - 2 || scrollState.value + viewport - bottomPadPx >= contentMax)) {
                                        onNextChapter()
                                    } else {
                                        coroutineScope.launch {
                                            val target = nextPageSeamState.value.coerceAtMost(scrollState.maxValue)
                                            scrollState.scrollTo(target)
                                        }
                                    }
                                }
                                false -> {
                                    onPageTurn()
                                    if (scrollState.value <= 2 && currentChapterIndex > 0) {
                                        onPreviousChapter()
                                    } else {
                                        coroutineScope.launch {
                                            val currLayout = textLayoutState.value
                                            val target = if (currLayout != null && currLayout.lineCount > 0) {
                                                var pos = 0
                                                var prevStart = 0
                                                var guard = 0
                                                while (guard < 4000 && pos < scrollState.value - 4) {
                                                    prevStart = pos
                                                    val visBot = pos + viewport - bottomPadPx
                                                    var lCurr = -1
                                                    var fNext = -1
                                                    for (i in 0 until currLayout.lineCount) {
                                                        val b = currLayout.getLineBottom(i)
                                                        val t = currLayout.getLineTop(i)
                                                        if (b <= pos + 2f) continue
                                                        if (b <= visBot) {
                                                            lCurr = i
                                                        } else if (t > pos + 2f) {
                                                            fNext = i
                                                            break
                                                        } else {
                                                            lCurr = i
                                                        }
                                                    }
                                                    if (fNext == -1) break
                                                    val nextPos = if (lCurr != -1) {
                                                        ((currLayout.getLineBottom(lCurr) + currLayout.getLineTop(fNext)) * 0.5f).roundToInt()
                                                    } else {
                                                        currLayout.getLineTop(fNext).roundToInt()
                                                    }
                                                    if (nextPos <= pos + 2 || nextPos >= scrollState.value - 4) break
                                                    pos = nextPos
                                                    guard++
                                                }
                                                prevStart
                                            } else {
                                                (scrollState.value - fallbackStepPx).coerceAtLeast(0)
                                            }
                                            scrollState.scrollTo(target.coerceAtLeast(0))
                                        }
                                    }
                                }
                                null -> onToggleControls()
                            }
                        }
                    }
                )
            }
    ) {
        SelectionContainer {
            if (enableTwoColumn) {
                // 平板横屏：优雅双栏阅读
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 32.dp, vertical = 24.dp)
                        .verticalScroll(scrollState)
                ) {
                    // 左栏
                    Text(
                        text = parsedResult.fullText,
                        style = scrollTextStyle,
                        modifier = Modifier.weight(1f)
                    )

                    Spacer(modifier = Modifier.width(36.dp))

                    // 右栏提供辅助阅读视觉
                    Text(
                        text = parsedResult.fullText,
                        style = scrollTextStyle.copy(color = textColor.copy(alpha = 0.9f)),
                        modifier = Modifier.weight(1f)
                    )
                }
            } else {
                // 手机或平板竖屏单栏
                ColumnContainer(
                    text = parsedResult.fullText,
                    textStyle = scrollTextStyle,
                    bottomPadDp = with(density) { viewportPx.toDp() },
                    scrollState = scrollState,
                    onTextLayout = { textLayout = it },
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 20.dp, vertical = 16.dp)
                )
            }
        }
        if (!enableTwoColumn && bottomMaskPx > 0) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(with(density) { bottomMaskPx.toDp() })
                    .background(bgColor)
            )
        }
    }
}

@Composable
private fun TxtPageReader(
    parsedResult: TxtParsedResult,
    settings: ReaderSettings,
    currentChapterIndex: Int,
    initialCharOffset: Long,
    reserveBottomBar: Boolean,
    onProgressChanged: (progressPercent: Float, charOffset: Long, chapterIndex: Int, chapterFraction: Float, chapterPageIndex: Int, chapterPageCount: Int) -> Unit,
    onToggleControls: () -> Unit,
    onBottomEdgeTap: () -> Unit,
    onPageTurn: () -> Unit,
    onNextChapter: () -> Unit,
    onPreviousChapter: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bgColor = Color(settings.bgTheme.bgHex)
    val textColor = Color(settings.bgTheme.textHex)
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val bottomInset = if (reserveBottomBar) 64.dp else 16.dp
    val chapterStart = parsedResult.chapters.getOrNull(currentChapterIndex)?.startCharOffset ?: 0L
    val chapterEnd = parsedResult.chapters.getOrNull(currentChapterIndex + 1)?.startCharOffset ?: parsedResult.totalLength
    val chapterText = parsedResult.fullText.substring(
        chapterStart.toInt().coerceIn(0, parsedResult.fullText.length),
        chapterEnd.toInt().coerceIn(0, parsedResult.fullText.length)
    )
    val textStyle = LocalTextStyle.current.merge(
        TextStyle(
            fontSize = settings.fontSizeSp.sp,
            lineHeight = (settings.fontSizeSp * settings.lineSpacingMultiplier).sp,
            letterSpacing = 0.sp,
            color = textColor,
            platformStyle = PlatformTextStyle(includeFontPadding = false),
            lineHeightStyle = LineHeightStyle(
                alignment = LineHeightStyle.Alignment.Center,
                trim = LineHeightStyle.Trim.None
            )
        )
    )
    var pageIndex by remember { mutableIntStateOf(0) }
    var charAnchor by remember { mutableIntStateOf(-1) }
    var landOnLast by remember { mutableStateOf(false) }
    var lastChapter by remember { mutableIntStateOf(-1) }
    val pageIndexState = rememberUpdatedState(pageIndex)
    val pagesHolder = remember { mutableStateOf<List<TxtPageSlice>>(emptyList()) }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(bgColor)
            .pointerInput(currentChapterIndex, settings.pageTurnHand) {
                detectTapGestures { offset ->
                    val width = size.width.toFloat()
                    val height = size.height
                    val pages = pagesHolder.value
                    val index = pageIndexState.value
                    if (offset.y >= height * 0.82f) {
                        onBottomEdgeTap()
                    } else {
                        when (settings.pageTurnHand.movesForward(offset.x, width)) {
                            true -> {
                                onPageTurn()
                                if (index < pages.lastIndex) {
                                    pageIndex = index + 1
                                    charAnchor = pages.getOrNull(pageIndex)?.range?.first ?: 0
                                } else {
                                    onNextChapter()
                                }
                            }
                            false -> {
                                onPageTurn()
                                if (index > 0) {
                                    pageIndex = index - 1
                                    charAnchor = pages.getOrNull(pageIndex)?.range?.first ?: 0
                                } else if (currentChapterIndex > 0) {
                                    landOnLast = true
                                    onPreviousChapter()
                                }
                            }
                            null -> onToggleControls()
                        }
                    }
                }
            }
    ) {
        val horizontalPadPx = with(density) { 20.dp.roundToPx() } * 2
        val topPadPx = with(density) { 16.dp.roundToPx() }
        val bottomPadPx = with(density) { bottomInset.roundToPx() }
        val widthPx = (constraints.maxWidth - horizontalPadPx).coerceAtLeast(1)
        val heightPx = (constraints.maxHeight - topPadPx - bottomPadPx).coerceAtLeast(1)
        val linePx = with(density) {
            (settings.fontSizeSp * settings.lineSpacingMultiplier).sp.toPx()
        }.toInt().coerceAtLeast(1)
        val slackPx = (linePx * 0.45f).toInt().coerceAtLeast(12)
        val pages = remember(chapterText, textStyle, widthPx, heightPx, slackPx) {
            paginateText(textMeasurer, chapterText, textStyle, widthPx, heightPx, slackPx)
        }
        pagesHolder.value = pages

        LaunchedEffect(currentChapterIndex, pages) {
            if (pages.isEmpty()) return@LaunchedEffect
            val switched = lastChapter != currentChapterIndex
            val localAnchor = when {
                landOnLast -> {
                    landOnLast = false
                    Int.MAX_VALUE
                }
                switched -> {
                    val delta = initialCharOffset - chapterStart
                    if (delta > Int.MAX_VALUE) Int.MAX_VALUE else delta.toInt().coerceAtLeast(0)
                }
                else -> charAnchor.coerceAtLeast(0)
            }
            lastChapter = currentChapterIndex
            val found = pages.indexOfLast { it.range.first <= localAnchor }
            pageIndex = found.coerceAtLeast(0)
            charAnchor = pages[pageIndex].range.first
        }

        LaunchedEffect(pageIndex, pages, currentChapterIndex) {
            if (pages.isEmpty() || parsedResult.totalLength <= 0L) return@LaunchedEffect
            val page = pages.getOrNull(pageIndex) ?: return@LaunchedEffect
            val charOffset = chapterStart + page.range.first
            val bookPercent = (charOffset.toFloat() / parsedResult.totalLength.toFloat()).coerceIn(0f, 1f)
            val chapterFraction = if (pages.size <= 1) {
                0f
            } else {
                (pageIndex.toFloat() / (pages.size - 1).toFloat()).coerceIn(0f, 1f)
            }
            onProgressChanged(bookPercent, charOffset, currentChapterIndex, chapterFraction, pageIndex, pages.size.coerceAtLeast(1))
        }

        val page = pages.getOrNull(pageIndex.coerceIn(0, pages.lastIndex.coerceAtLeast(0)))
        val visible = page?.text ?: ""
        SelectionContainer {
            Text(
                text = visible,
                style = textStyle,
                softWrap = false,
                modifier = Modifier
                    .fillMaxSize()
                    .clipToBounds()
                    .padding(start = 20.dp, end = 20.dp, top = 16.dp)
            )
        }
    }
}

private data class TxtPageSlice(
    val range: IntRange,
    val text: String
)

private fun paginateText(
    measurer: androidx.compose.ui.text.TextMeasurer,
    text: String,
    style: TextStyle,
    widthPx: Int,
    pageHeightPx: Int,
    slackPx: Int
): List<TxtPageSlice> {
    if (text.isEmpty()) return listOf(TxtPageSlice(0..0, ""))
    val layout = measurer.measure(
        text = text,
        style = style,
        overflow = TextOverflow.Clip,
        constraints = Constraints(maxWidth = widthPx)
    )
    if (layout.lineCount == 0) return listOf(TxtPageSlice(0 until text.length, text))
    val limit = (pageHeightPx - slackPx).coerceAtLeast(1)
    val pages = mutableListOf<TxtPageSlice>()
    var line = 0
    while (line < layout.lineCount) {
        val top = layout.getLineTop(line)
        var last = line
        while (last + 1 < layout.lineCount && layout.getLineBottom(last + 1) - top <= limit) {
            last++
        }
        val start = layout.getLineStart(line)
        val end = layout.getLineEnd(last, visibleEnd = false).coerceAtLeast(start + 1)
        val sb = StringBuilder()
        for (i in line..last) {
            val lStart = layout.getLineStart(i).coerceIn(0, text.length)
            val lEnd = layout.getLineEnd(i, visibleEnd = true).coerceIn(lStart, text.length)
            sb.append(text, lStart, lEnd)
            if (i < last) sb.append('\n')
        }
        pages.add(TxtPageSlice(start until end.coerceAtMost(text.length), sb.toString()))
        if (last >= layout.lineCount - 1) break
        line = last + 1
    }
    return pages.ifEmpty { listOf(TxtPageSlice(0 until text.length, text)) }
}

@Composable
private fun ColumnContainer(
    text: String,
    textStyle: TextStyle,
    bottomPadDp: androidx.compose.ui.unit.Dp,
    scrollState: androidx.compose.foundation.ScrollState,
    onTextLayout: (TextLayoutResult) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.verticalScroll(scrollState)) {
        Text(
            text = text,
            style = textStyle,
            onTextLayout = onTextLayout,
            modifier = Modifier.padding(bottom = bottomPadDp)
        )
    }
}
