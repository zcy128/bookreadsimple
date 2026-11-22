package com.example.bookreader.ui.reader

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.bookreader.domain.model.PageTurnHand
import com.example.bookreader.domain.model.ReadingMode
import com.example.bookreader.domain.model.ReaderSettings
import com.example.bookreader.parser.EpubParsedBook
import com.example.bookreader.ui.locale.LocalAppStrings
import java.io.File
import java.util.concurrent.atomic.AtomicReference

data class FootnoteDialogState(
    val title: String,
    val content: String,
    val anchorId: String = "",
    val targetChapterIndex: Int? = null,
    val targetChapterTitle: String? = null
)

class EpubJsBridge(
    private val mainHandler: Handler,
    private val onProgress: (Float) -> Unit,
    private val onCenterTap: () -> Unit,
    private val onNext: () -> Unit,
    private val onPrev: () -> Unit,
    private val onWordSelected: (String) -> Unit = {},
    private val onFootnote: (noteTitle: String, noteContent: String, anchorId: String) -> Unit = { _, _, _ -> },
    private val onNavigateChapter: (chapterIndex: Int, anchorId: String?) -> Unit = { _, _ -> },
    private val onExternalLink: (url: String) -> Unit = {},
    private val onResolveExternalFootnote: (relativeHref: String, linkText: String) -> Unit = { _, _ -> },
    private val onBottomEdge: () -> Unit = {},
    private val onChapterPages: (pageIndex: Int, pageCount: Int) -> Unit = { _, _ -> },
    private val onPageTurn: () -> Unit = {},
    private val onPageClip: (Float) -> Unit = {}
) {
    @JavascriptInterface
    fun reportScroll(percent: Float) {
        mainHandler.post { onProgress(percent) }
    }

    @JavascriptInterface
    fun onScreenCenterTapped() {
        mainHandler.post { onCenterTap() }
    }

    @JavascriptInterface
    fun onNextChapter() {
        mainHandler.post { onNext() }
    }

    @JavascriptInterface
    fun onPrevChapter() {
        mainHandler.post { onPrev() }
    }

    @JavascriptInterface
    fun onTextSelected(text: String) {
        mainHandler.post { onWordSelected(text) }
    }

    @JavascriptInterface
    fun showFootnote(title: String, content: String, anchorId: String) {
        mainHandler.post { onFootnote(title, content, anchorId) }
    }

    @JavascriptInterface
    fun navigateToChapter(chapterIndex: Int, anchorId: String) {
        mainHandler.post { onNavigateChapter(chapterIndex, anchorId.ifEmpty { null }) }
    }

    @JavascriptInterface
    fun openExternalUrl(url: String) {
        mainHandler.post { onExternalLink(url) }
    }

    @JavascriptInterface
    fun resolveAndShowExternalFootnote(relativeHref: String, linkText: String) {
        mainHandler.post { onResolveExternalFootnote(relativeHref, linkText) }
    }

    @JavascriptInterface
    fun onBottomEdgeTapped() {
        mainHandler.post { onBottomEdge() }
    }

    @JavascriptInterface
    fun onPageTurned() {
        mainHandler.post { onPageTurn() }
    }

    @JavascriptInterface
    fun onPageClip(height: Double) {
        mainHandler.post { onPageClip(height.toFloat()) }
    }

    @JavascriptInterface
    fun reportChapterPages(pageIndex: Double, pageCount: Double) {
        mainHandler.post { onChapterPages(pageIndex.toInt(), pageCount.toInt()) }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun EpubReaderView(
    epubBook: EpubParsedBook,
    currentChapterIndex: Int,
    initialScrollFraction: Float,
    readerSettings: ReaderSettings,
    isTablet: Boolean,
    onChapterProgressChanged: (percent: Float) -> Unit,
    onToggleControls: () -> Unit,
    onNextChapter: () -> Unit,
    onPreviousChapter: () -> Unit,
    onSelectChapter: (Int) -> Unit,
    modifier: Modifier = Modifier,
    onBottomEdgeTap: () -> Unit = {},
    onPageTurn: () -> Unit = {},
    onChapterPagesChanged: (pageIndex: Int, pageCount: Int) -> Unit = { _, _ -> },
    onLookupWord: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val strings = LocalAppStrings.current
    val currentChapter = epubBook.chapters.getOrNull(currentChapterIndex) ?: epubBook.chapters.firstOrNull()
    val chapterFile = currentChapter?.let { File(epubBook.extractedDir, it.contentPath) }

    val bgColorHex = String.format("#%06X", (0xFFFFFF and readerSettings.bgTheme.bgHex.toInt()))
    val textColorHex = String.format("#%06X", (0xFFFFFF and readerSettings.bgTheme.textHex.toInt()))

    // 记录章节加载时的初始滚动位置，恢复后重置为 0，避免后续章节跳跃
    val pendingScrollFractionRef = remember { AtomicReference(initialScrollFraction) }
    remember(currentChapterIndex) {
        pendingScrollFractionRef.set(initialScrollFraction)
        currentChapterIndex
    }
    val pageModeState = remember { mutableStateOf(false) }
    pageModeState.value = readerSettings.readingMode == ReadingMode.PAGE
    val pageMode = pageModeState.value
    val leftHandState = remember { mutableStateOf(false) }
    leftHandState.value = readerSettings.pageTurnHand == PageTurnHand.LEFT
    val leftHand = leftHandState.value

    var footnoteDialogState by remember { mutableStateOf<FootnoteDialogState?>(null) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var pageClipPx by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(currentChapterIndex) {
        pageClipPx = 0f
    }

    val currentChapterIndexState = rememberUpdatedState(currentChapterIndex)
    val onChapterProgressChangedState = rememberUpdatedState(onChapterProgressChanged)
    val onToggleControlsState = rememberUpdatedState(onToggleControls)
    val onNextChapterState = rememberUpdatedState(onNextChapter)
    val onPreviousChapterState = rememberUpdatedState(onPreviousChapter)
    val onSelectChapterState = rememberUpdatedState(onSelectChapter)
    val onBottomEdgeTapState = rememberUpdatedState(onBottomEdgeTap)
    val onPageTurnState = rememberUpdatedState(onPageTurn)
    val onChapterPagesChangedState = rememberUpdatedState(onChapterPagesChanged)
    val onLookupWordState = rememberUpdatedState(onLookupWord)

    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val jsBridge: EpubJsBridge = remember {
        EpubJsBridge(
            mainHandler = mainHandler,
            onProgress = { percent -> onChapterProgressChangedState.value(percent) },
            onCenterTap = { onToggleControlsState.value() },
            onNext = { onNextChapterState.value() },
            onPrev = { onPreviousChapterState.value() },
            onWordSelected = { onLookupWordState.value(it) },
            onFootnote = { title, content, anchorId ->
                footnoteDialogState = FootnoteDialogState(
                    title = title,
                    content = content,
                    anchorId = anchorId
                )
            },
            onNavigateChapter = { chapterIndex, _ ->
                onSelectChapterState.value(chapterIndex)
            },
            onExternalLink = { url ->
                try {
                    val trimmed = url.trim()
                    val lower = trimmed.lowercase()
                    if (lower.startsWith("http://") || lower.startsWith("https://")) {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(trimmed)).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(intent)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            },
            onBottomEdge = { onBottomEdgeTapState.value() },
            onPageTurn = { onPageTurnState.value() },
            onPageClip = { pageClipPx = it.coerceAtLeast(0f) },
            onChapterPages = { index, count -> onChapterPagesChangedState.value(index, count) },
            onResolveExternalFootnote = { relativeHref, linkText ->
                val activeChapterIndex = currentChapterIndexState.value
                val currChapter = epubBook.chapters.getOrNull(activeChapterIndex) ?: epubBook.chapters.firstOrNull()
                if (currChapter != null) {
                    val currFile = File(epubBook.extractedDir, currChapter.contentPath)
                    val pathPart = relativeHref.substringBefore('#')
                    val anchorPart = if (relativeHref.contains('#')) relativeHref.substringAfter('#') else ""
                    val targetFile = if (pathPart.isNotEmpty()) {
                        File(currFile.parentFile, pathPart).canonicalFile
                    } else currFile

                    val matchedChapterIdx = epubBook.chapters.indexOfFirst {
                        File(epubBook.extractedDir, it.contentPath).canonicalPath == targetFile.canonicalPath
                    }

                    if (anchorPart.isNotEmpty() && targetFile.exists()) {
                        val extractedNote = extractAnchorTextFromFile(targetFile, anchorPart)
                        if (extractedNote != null && extractedNote.length > linkText.length && extractedNote != linkText) {
                            footnoteDialogState = FootnoteDialogState(
                                title = linkText.ifEmpty { "注释" },
                                content = extractedNote,
                                anchorId = anchorPart,
                                targetChapterIndex = if (matchedChapterIdx != -1 && matchedChapterIdx != activeChapterIndex) matchedChapterIdx else null,
                                targetChapterTitle = if (matchedChapterIdx != -1) epubBook.chapters.getOrNull(matchedChapterIdx)?.title else null
                            )
                            return@EpubJsBridge
                        }
                    }

                    if (matchedChapterIdx != -1 && matchedChapterIdx != activeChapterIndex) {
                        onSelectChapterState.value(matchedChapterIdx)
                    }
                }
            }
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(readerSettings.bgTheme.bgHex))
    ) {
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    webViewRef = this
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    setBackgroundColor(AndroidColor.TRANSPARENT)
                    isVerticalScrollBarEnabled = false
                    isHorizontalScrollBarEnabled = false
                    overScrollMode = WebView.OVER_SCROLL_NEVER
                    var reportedCssHeight = 0
                    addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                        if (v.height <= 0) return@addOnLayoutChangeListener
                        val density = v.resources.displayMetrics.density.coerceAtLeast(1f)
                        val cssHeight = (v.height / density).toInt()
                        if (cssHeight == reportedCssHeight) return@addOnLayoutChangeListener
                        reportedCssHeight = cssHeight
                        evaluateJavascript(
                            "window.__brViewHeight=$cssHeight;if(window.__brRefit)window.__brRefit();",
                            null
                        )
                    }
                    setOnTouchListener { v, event ->
                        if (event.actionMasked == MotionEvent.ACTION_UP) {
                            v.performClick()
                        }
                        pageModeState.value && event.actionMasked == MotionEvent.ACTION_MOVE
                    }
                    this.settings.javaScriptEnabled = true
                    this.settings.domStorageEnabled = true
                    this.settings.allowFileAccess = true
                    this.settings.cacheMode = WebSettings.LOAD_DEFAULT

                    registerEpubBridge(jsBridge)

                    webChromeClient = object : WebChromeClient() {
                        override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                            android.util.Log.d("BookReaderWebView", "${consoleMessage?.message()} [line ${consoleMessage?.lineNumber()}]")
                            return true
                        }
                    }

                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            val fractionToRestore = pendingScrollFractionRef.getAndSet(0f)
                            injectStylingAndScript(
                                webView = this@apply,
                                bgColor = bgColorHex,
                                textColor = textColorHex,
                                fontSizeSp = readerSettings.fontSizeSp,
                                lineSpacing = readerSettings.lineSpacingMultiplier,
                                isTablet = isTablet,
                                initialScrollFraction = fractionToRestore,
                                pageMode = pageModeState.value,
                                leftHand = leftHandState.value
                            )
                        }

                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                            val targetUri = request?.url ?: return false
                            val uriStr = targetUri.toString()
                            if (uriStr.startsWith("http://") || uriStr.startsWith("https://")) {
                                try {
                                    val intent = Intent(Intent.ACTION_VIEW, targetUri).apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    view?.context?.startActivity(intent)
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                }
                                return true
                            }
                            if (uriStr.startsWith("file://")) {
                                val targetFileName = File(targetUri.path ?: "").name
                                val matched = epubBook.chapters.indexOfFirst {
                                    it.contentPath.endsWith(targetFileName)
                                }
                                if (matched != -1 && matched != currentChapterIndex) {
                                    onSelectChapter(matched)
                                    return true
                                }
                            }
                            return true
                        }
                    }
                }
            },
            update = { webView ->
                webViewRef = webView
                if (chapterFile != null && chapterFile.exists()) {
                    val fileUrl = "file://${chapterFile.absolutePath}"
                    if (webView.url != fileUrl) {
                        webView.loadUrl(fileUrl)
                    } else {
                        // 动态更新主题或字号
                        injectStylingAndScript(
                            webView = webView,
                            bgColor = bgColorHex,
                            textColor = textColorHex,
                            fontSizeSp = readerSettings.fontSizeSp,
                            lineSpacing = readerSettings.lineSpacingMultiplier,
                            isTablet = isTablet,
                            initialScrollFraction = 0f,
                            pageMode = pageMode,
                            leftHand = leftHand
                        )
                    }
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 12.dp)
        )

        if (pageClipPx > 0.5f) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(pageClipPx.dp)
                    .background(Color(readerSettings.bgTheme.bgHex))
            )
        }

        // 注释专属弹出弹窗（避免读者跳出阅读流）
        if (footnoteDialogState != null) {
            val note = footnoteDialogState!!
            AlertDialog(
                onDismissRequest = { footnoteDialogState = null },
                icon = {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.MenuBook,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                },
                title = {
                    Text(
                        text = if (note.title.isNotEmpty()) "${strings.footnoteTitle} · ${note.title}" else strings.footnoteTitle,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                    ) {
                        SelectionContainer {
                            Text(
                                text = note.content,
                                style = MaterialTheme.typography.bodyMedium,
                                lineHeight = 22.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        if (note.targetChapterTitle != null) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = note.targetChapterTitle,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                confirmButton = {
                    Row {
                        // 复制注释内容
                        TextButton(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText(strings.footnoteTitle, note.content))
                                Toast.makeText(context, strings.copiedToClipboard, Toast.LENGTH_SHORT).show()
                            }
                        ) {
                            Text(strings.copyText)
                        }

                        // 若在本章内，允许平滑定位到该元素
                        if (note.anchorId.isNotEmpty() && note.targetChapterIndex == null) {
                            TextButton(
                                onClick = {
                                    webViewRef?.evaluateJavascript("window.bookReaderScrollToAnchor('${note.anchorId}');", null)
                                    footnoteDialogState = null
                                }
                            ) {
                                Text("OK")
                            }
                        }

                        // 若跨章节，支持跳转至目标章节
                        if (note.targetChapterIndex != null) {
                            Button(
                                onClick = {
                                    onSelectChapter(note.targetChapterIndex)
                                    footnoteDialogState = null
                                }
                            ) {
                                Text(note.targetChapterTitle ?: "OK")
                            }
                        }
                    }
                },
                dismissButton = {
                    TextButton(onClick = { footnoteDialogState = null }) {
                        Text(strings.cancel)
                    }
                }
            )
        }
    }
}

private fun extractAnchorTextFromFile(file: File, anchorId: String): String? {
    if (!file.exists() || anchorId.isEmpty()) return null
    return try {
        val content = file.readText()
        val decodedId = try { Uri.decode(anchorId) } catch (e: Exception) { anchorId }
        val idsToTry = listOf(anchorId, decodedId).distinct()

        var matchRange: IntRange? = null
        for (id in idsToTry) {
            val pattern = Regex("""(?i)(?:id|name)\s*=\s*["']${Regex.escape(id)}["']""")
            val match = pattern.find(content)
            if (match != null) {
                matchRange = match.range
                break
            }
        }
        val match = matchRange ?: return null
        val tagEnd = content.indexOf('>', match.last)
        if (tagEnd == -1) return null

        val rawSnippet = content.substring(tagEnd + 1, (tagEnd + 2500).coerceAtMost(content.length))
        val textSnippet = rawSnippet
            .substringBefore("</p>")
            .substringBefore("</div>")
            .substringBefore("</li>")
            .substringBefore("</aside>")
            .substringBefore("</dd>")
            .replace(Regex("<[^>]*>"), "")
            .replace("&nbsp;", " ")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&#x20;", " ")
            .replace("↩", "")
            .replace("↑", "")
            .trim()

        textSnippet.ifEmpty { null }
    } catch (e: Exception) {
        null
    }
}

private fun injectStylingAndScript(
    webView: WebView,
    bgColor: String,
    textColor: String,
    fontSizeSp: Int,
    lineSpacing: Float,
    isTablet: Boolean,
    initialScrollFraction: Float,
    pageMode: Boolean,
    leftHand: Boolean
) {
    val density = webView.resources.displayMetrics.density.coerceAtLeast(1f)
    val viewHeightCss = if (webView.height > 0) (webView.height / density).toInt() else 0
    val css = """
        ::-webkit-scrollbar {
            display: none !important;
            width: 0 !important;
            height: 0 !important;
        }

        html {
            background-color: $bgColor !important;
            color: $textColor !important;
            margin: 0 !important;
            padding: 0 !important;
            height: auto !important;
            min-height: 100% !important;
            overflow-y: visible !important;
            overflow-x: hidden !important;
            transform: none !important;
        }

        body {
            background-color: $bgColor !important;
            color: $textColor !important;
            font-size: ${fontSizeSp}px !important;
            line-height: $lineSpacing !important;
            padding: 8px 20px 100vh 20px !important;
            margin: 0 auto !important;
            max-width: ${if (isTablet) "820px" else "100%"} !important;
            box-sizing: border-box !important;
            word-break: break-word !important;
            overflow-y: visible !important;
            overflow-x: hidden !important;
            height: auto !important;
            min-height: 100vh !important;
            position: static !important;
            -webkit-user-select: text !important;
            user-select: text !important;
            -webkit-tap-highlight-color: transparent !important;
            transform: none !important;
        }

        /* 解决 Calibre 及各类转换器对高度与滚动的限制（排除分页底部遮罩） */
        body *:not(#bookreader-page-mask) {
            max-height: none !important;
        }

        div:not(#bookreader-page-mask), section, article, main, [class*=calibre]:not(#bookreader-page-mask) {
            height: auto !important;
            min-height: 0 !important;
            max-height: none !important;
            overflow: visible !important;
            position: static !important;
            line-height: $lineSpacing !important;
        }

        #bookreader-page-mask {
            position: fixed !important;
            left: 0 !important;
            right: 0 !important;
            bottom: 0 !important;
            width: 100% !important;
            z-index: 2147483646 !important;
            pointer-events: none !important;
            background-color: $bgColor !important;
            margin: 0 !important;
            padding: 0 !important;
            border: none !important;
            min-height: 0 !important;
            max-height: none !important;
        }

        img, svg {
            max-width: 100% !important;
            max-height: 85vh !important;
            height: auto !important;
            display: block !important;
            margin: 12px auto !important;
        }

        p, li, blockquote, dd, dt, span {
            line-height: $lineSpacing !important;
        }

        p {
            margin: 0.6em 0 !important;
            text-align: justify !important;
        }

        /* 强化注释链接样式，放大触摸点击响应热区，使读者容易辨识并精准点击 */
        a {
            color: #1E88E5 !important;
            text-decoration: underline !important;
            text-decoration-style: dotted !important;
            text-underline-offset: 3px !important;
            cursor: pointer !important;
            padding: 2px 4px !important;
            border-radius: 3px !important;
        }

        a:active {
            background-color: rgba(30, 136, 229, 0.15) !important;
        }

        /* 注释锚点定位高亮脉冲动画 */
        @keyframes bookreader-highlight-pulse {
            0% { background-color: rgba(255, 215, 0, 0.45); }
            50% { background-color: rgba(255, 215, 0, 0.25); }
            100% { background-color: transparent; }
        }

        .bookreader-anchor-highlight {
            animation: bookreader-highlight-pulse 2.2s ease-out !important;
            border-radius: 4px !important;
        }
    """.trimIndent()

    val safeCss = css
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("'", "\\'")
        .replace("\n", " ")
        .replace("\r", "")

    val js = """
        (function() {
            try {
                var style = document.getElementById('bookreader-custom-style');
                if (!style) {
                    style = document.createElement('style');
                    style.id = 'bookreader-custom-style';
                    document.head.appendChild(style);
                }
                style.textContent = '$safeCss';
                if ($viewHeightCss > 0 && !window.__brViewHeight) {
                    window.__brViewHeight = $viewHeightCss;
                }
                window.__brPageMode = ${if (pageMode) "true" else "false"};
                window.__brLeftHand = ${if (leftHand) "true" else "false"};
                window.__brFontSize = $fontSizeSp;
                window.__brLineHeight = $lineSpacing;

                var bottomPad = 12;

                function getScrollTop() {
                    return window.pageYOffset || document.documentElement.scrollTop || document.body.scrollTop || 0;
                }

                function usableHeight() {
                    var ih = window.innerHeight || document.documentElement.clientHeight || 0;
                    var vh = window.__brViewHeight || 0;
                    if (ih > 0) return ih;
                    return vh;
                }

                function getRawMaxScroll() {
                    var docHeight = Math.max(
                        document.body.scrollHeight || 0,
                        document.documentElement.scrollHeight || 0,
                        document.body.offsetHeight || 0,
                        document.documentElement.offsetHeight || 0
                    );
                    return Math.max(0, docHeight - usableHeight());
                }

                function getMaxScroll() {
                    var starts = getPageStarts();
                    if (starts && starts.length > 0) {
                        return Math.max(0, starts[starts.length - 1]);
                    }
                    return getRawMaxScroll();
                }

                function setScroll(newTop, smooth) {
                    var max = Math.max(getRawMaxScroll(), getMaxScroll());
                    var clamped = Math.max(0, Math.min(max, Math.round(newTop)));
                    if (smooth) {
                        window.scrollTo({ top: clamped, behavior: 'smooth' });
                    } else {
                        window.scrollTo(0, clamped);
                        document.documentElement.scrollTop = clamped;
                        document.body.scrollTop = clamped;
                    }
                    var bounds = getLineBounds();
                    updatePageMask(clamped, bounds);
                    reportPages();
                }

                function doScroll(newTop) {
                    setScroll(newTop, false);
                }

                function lineBoxHeight() {
                    var fs = window.__brFontSize || 18;
                    var mult = window.__brLineHeight || 1.4;
                    var h = Math.round(fs * mult);
                    return h > 0 ? h : 24;
                }

                function reportClip(px) {
                    if (window.AndroidEpub && window.AndroidEpub.onPageClip) {
                        window.AndroidEpub.onPageClip(Math.max(0, px));
                    }
                }

                var lineBoundsCache = null;
                var lineBoundsKey = '';

                function getLineBounds() {
                    var key = (document.body.scrollHeight || 0) + 'x' + usableHeight() + 'x' +
                        (window.__brFontSize || 18) + 'x' + (window.__brLineHeight || 1.4);
                    if (lineBoundsCache && lineBoundsKey === key) return lineBoundsCache;
                    var scroll = getScrollTop();
                    var fs = window.__brFontSize || 18;
                    var raw = [];
                    function pushRect(rect, isBlock) {
                        if (!rect || rect.height < 1 || rect.width < 1) return;
                        var t = rect.top + scroll;
                        var b = rect.bottom + scroll;
                        var h = b - t;
                        if (!isBlock && h > fs * 1.12) {
                            var trim = Math.min(3.5, (h - fs * 1.08) * 0.42);
                            t += trim;
                            b -= trim;
                        }
                        raw.push({
                            top: t,
                            bottom: b,
                            mid: (t + b) * 0.5,
                            height: b - t,
                            isBlock: !!isBlock
                        });
                    }
                    var walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, {
                        acceptNode: function(node) {
                            if (!node.nodeValue || !node.nodeValue.trim()) return NodeFilter.FILTER_REJECT;
                            var parent = node.parentElement;
                            if (!parent) return NodeFilter.FILTER_REJECT;
                            if (parent.id === 'bookreader-page-mask') return NodeFilter.FILTER_REJECT;
                            var tag = parent.tagName;
                            if (tag === 'SCRIPT' || tag === 'STYLE' || tag === 'NOSCRIPT') return NodeFilter.FILTER_REJECT;
                            return NodeFilter.FILTER_ACCEPT;
                        }
                    });
                    var textNode;
                    while ((textNode = walker.nextNode())) {
                        try {
                            var range = document.createRange();
                            range.selectNodeContents(textNode);
                            var rects = range.getClientRects();
                            for (var i = 0; i < rects.length; i++) pushRect(rects[i], false);
                        } catch (e) {}
                    }
                    var blocks = document.body.querySelectorAll('img, svg, video, canvas');
                    for (var j = 0; j < blocks.length; j++) {
                        pushRect(blocks[j].getBoundingClientRect(), true);
                    }
                    raw.sort(function(a, b) { return a.mid - b.mid || a.top - b.top; });
                    var maxSameLineDelta = Math.max(8, lineBoxHeight() * 0.42);
                    var merged = [];
                    for (var k = 0; k < raw.length; k++) {
                        var item = raw[k];
                        if (merged.length > 0) {
                            var prev = merged[merged.length - 1];
                            if (!item.isBlock && !prev.isBlock && Math.abs(item.mid - prev.mid) < maxSameLineDelta) {
                                prev.top = Math.min(prev.top, item.top);
                                prev.bottom = Math.max(prev.bottom, item.bottom);
                                if (item.height > prev.height) {
                                    prev.mid = item.mid;
                                    prev.height = item.height;
                                }
                                continue;
                            }
                        }
                        merged.push({
                            top: item.top,
                            bottom: item.bottom,
                            mid: item.mid,
                            height: item.height,
                            isBlock: item.isBlock
                        });
                    }
                    merged.sort(function(a, b) { return a.top - b.top || a.bottom - b.bottom; });
                    for (var m = 0; m + 1 < merged.length; m++) {
                        var curr = merged[m];
                        var nxt = merged[m + 1];
                        if (!curr.isBlock && !nxt.isBlock && nxt.top < curr.bottom + 2) {
                            var seamMid = (curr.bottom + nxt.top) * 0.5;
                            curr.bottom = seamMid - 1;
                            nxt.top = seamMid + 1;
                        }
                    }
                    lineBoundsCache = merged;
                    lineBoundsKey = key;
                    return merged;
                }

                function computePageBreak(scrollTop, bounds) {
                    var h = usableHeight();
                    var visibleBottom = scrollTop + h - bottomPad;
                    var lines = bounds || [];
                    if (lines.length === 0) {
                        var fallbackStep = Math.max(lineBoxHeight() * 3, h - bottomPad);
                        return { hasNext: true, seam: Math.round(scrollTop + fallbackStep), maskHeight: 0 };
                    }
                    var contentEnd = lines[lines.length - 1].bottom;
                    if (contentEnd <= visibleBottom + 1) {
                        return { hasNext: false, seam: scrollTop, maskHeight: 0 };
                    }
                    var lastCurr = null;
                    var firstNext = null;
                    for (var i = 0; i < lines.length; i++) {
                        var box = lines[i];
                        if (box.bottom <= scrollTop + 2) continue;
                        if (box.bottom <= visibleBottom) {
                            lastCurr = box;
                        } else if (box.top > scrollTop + 2) {
                            firstNext = box;
                            break;
                        } else {
                            lastCurr = box;
                        }
                    }
                    if (!firstNext) {
                        return { hasNext: false, seam: scrollTop, maskHeight: 0 };
                    }
                    var seam;
                    if (lastCurr) {
                        var gap = firstNext.top - lastCurr.bottom;
                        if (gap <= 10) {
                            seam = Math.round((lastCurr.bottom + firstNext.top) * 0.5);
                        } else {
                            seam = Math.round(firstNext.top - 3);
                        }
                        if (seam < Math.ceil(lastCurr.bottom)) {
                            seam = Math.ceil(lastCurr.bottom);
                        }
                    } else {
                        seam = Math.round(firstNext.top - 2);
                    }
                    if (seam <= scrollTop + 2) {
                        seam = Math.round(scrollTop + Math.max(lineBoxHeight(), h - bottomPad));
                    }
                    var maskH = 0;
                    if (seam < scrollTop + h) {
                        maskH = Math.max(0, Math.ceil((scrollTop + h) - seam));
                    }
                    return { hasNext: true, seam: seam, maskHeight: maskH };
                }

                function pageStartAfter(scrollTop, bounds) {
                    var info = computePageBreak(scrollTop, bounds);
                    return info.hasNext ? info.seam : scrollTop;
                }

                var pageMapCache = null;
                var pageMapKey = '';
                function getPageStarts() {
                    var bounds = getLineBounds();
                    var key = lineBoundsKey + ':pages';
                    if (pageMapCache && pageMapKey === key) return pageMapCache;
                    var starts = [0];
                    if (bounds && bounds.length > 0) {
                        var pos = 0;
                        var rawMax = getRawMaxScroll();
                        var guard = 0;
                        while (guard < 4000) {
                            var info = computePageBreak(pos, bounds);
                            if (!info.hasNext || info.seam <= pos + 2 || info.seam > rawMax + 2) break;
                            starts.push(info.seam);
                            pos = info.seam;
                            guard++;
                        }
                    }
                    pageMapCache = starts;
                    pageMapKey = key;
                    return starts;
                }

                function reportPages() {
                    if (!window.AndroidEpub || !window.AndroidEpub.reportChapterPages) return;
                    var starts = getPageStarts();
                    var scroll = getScrollTop();
                    var index = 0;
                    for (var i = 0; i < starts.length; i++) {
                        if (starts[i] <= scroll + 6) index = i;
                        else break;
                    }
                    window.AndroidEpub.reportChapterPages(index, Math.max(1, starts.length));
                }

                function clearPageMask() {
                    var mask = document.getElementById('bookreader-page-mask');
                    if (mask) mask.style.setProperty('display', 'none', 'important');
                    reportClip(0);
                }

                function updatePageMask(scrollTop, bounds) {
                    var info = computePageBreak(scrollTop, bounds);
                    if (!info.hasNext || info.maskHeight <= 0) {
                        clearPageMask();
                        return;
                    }
                    reportClip(info.maskHeight);
                    var mask = document.getElementById('bookreader-page-mask');
                    if (!mask && document.body) {
                        mask = document.createElement('div');
                        mask.id = 'bookreader-page-mask';
                        document.body.appendChild(mask);
                    }
                    if (mask) {
                        mask.style.setProperty('position', 'fixed', 'important');
                        mask.style.setProperty('left', '0', 'important');
                        mask.style.setProperty('right', '0', 'important');
                        mask.style.setProperty('bottom', '0', 'important');
                        mask.style.setProperty('width', '100%', 'important');
                        mask.style.setProperty('z-index', '2147483646', 'important');
                        mask.style.setProperty('pointer-events', 'none', 'important');
                        mask.style.setProperty('background-color', '$bgColor', 'important');
                        mask.style.setProperty('display', 'block', 'important');
                        mask.style.setProperty('height', info.maskHeight + 'px', 'important');
                    }
                }

                var fitTimer = 0;
                function scheduleFit() {
                    if (fitTimer) clearTimeout(fitTimer);
                    fitTimer = setTimeout(fitPageToLines, 50);
                }
                window.__brRefit = function() {
                    lineBoundsCache = null;
                    pageMapCache = null;
                    scheduleFit();
                };

                function fitPageToLines() {
                    var bounds = getLineBounds();
                    var starts = getPageStarts();
                    var scroll = getScrollTop();
                    if (window.__brPageMode && starts && starts.length > 0) {
                        var nearest = starts[0];
                        var bestDist = Math.abs(scroll - nearest);
                        for (var i = 1; i < starts.length; i++) {
                            var d = Math.abs(scroll - starts[i]);
                            if (d < bestDist) {
                                bestDist = d;
                                nearest = starts[i];
                            }
                        }
                        if (bestDist > 1 && bestDist <= lineBoxHeight() * 1.25) {
                            window.scrollTo(0, nearest);
                            document.documentElement.scrollTop = nearest;
                            document.body.scrollTop = nearest;
                            scroll = nearest;
                        }
                    }
                    updatePageMask(scroll, bounds || []);
                    reportPages();
                }

                if (window.__brOnResize) window.removeEventListener('resize', window.__brOnResize);
                window.__brOnResize = function() {
                    lineBoundsCache = null;
                    pageMapCache = null;
                    scheduleFit();
                };
                window.addEventListener('resize', window.__brOnResize);
                var scrollFitTimer = 0;
                if (window.__brOnScroll) window.removeEventListener('scroll', window.__brOnScroll);
                window.__brOnScroll = function() {
                    if (scrollFitTimer) clearTimeout(scrollFitTimer);
                    scrollFitTimer = setTimeout(fitPageToLines, 80);
                };
                window.addEventListener('scroll', window.__brOnScroll, { passive: true });

                var lastChapterTurnTime = 0;

                function pageNext() {
                    var now = Date.now();
                    if (now - lastChapterTurnTime < 500) return;

                    var current = getScrollTop();
                    var max = getMaxScroll();

                    if (max <= 4 || current >= max - 4) {
                        lastChapterTurnTime = now;
                        if (window.AndroidEpub) window.AndroidEpub.onNextChapter();
                        return;
                    }
                    var bounds = getLineBounds();
                    var target = null;
                    if (bounds && bounds.length > 0) {
                        var starts = getPageStarts();
                        for (var i = 0; i < starts.length; i++) {
                            if (starts[i] > current + 4) {
                                target = starts[i];
                                break;
                            }
                        }
                        if (target == null) {
                            var info = computePageBreak(current, bounds);
                            if (!info.hasNext || info.seam <= current + 2) {
                                lastChapterTurnTime = now;
                                if (window.AndroidEpub) window.AndroidEpub.onNextChapter();
                                return;
                            }
                            target = info.seam;
                        }
                    } else {
                        target = Math.min(max, current + Math.max(lineBoxHeight() * 3, usableHeight() - bottomPad));
                    }
                    setScroll(target, false);
                }

                function pagePrev() {
                    var now = Date.now();
                    if (now - lastChapterTurnTime < 500) return;

                    var current = getScrollTop();

                    if (current <= 8) {
                        lastChapterTurnTime = now;
                        if (window.AndroidEpub) window.AndroidEpub.onPrevChapter();
                        return;
                    }
                    var bounds = getLineBounds();
                    var target = 0;
                    if (bounds && bounds.length > 0) {
                        var starts = getPageStarts();
                        for (var j = starts.length - 1; j >= 0; j--) {
                            if (starts[j] < current - 4) {
                                target = starts[j];
                                break;
                            }
                        }
                    } else {
                        target = Math.max(0, current - Math.max(lineBoxHeight() * 3, usableHeight() - bottomPad));
                    }
                    setScroll(Math.max(0, target), false);
                }

                // 查找锚点目标元素（支持 id、name、URL 编解码多形态）
                function findTargetElement(id) {
                    if (!id) return null;
                    var candidates = [id];
                    try {
                        var dec = decodeURIComponent(id);
                        if (dec !== id) candidates.push(dec);
                    } catch(e) {}
                    try {
                        var enc = encodeURIComponent(id);
                        if (enc !== id) candidates.push(enc);
                    } catch(e) {}

                    for (var i = 0; i < candidates.length; i++) {
                        var c = candidates[i];
                        var el = document.getElementById(c);
                        if (el) return el;
                        try {
                            el = document.querySelector('[name="' + CSS.escape(c) + '"]');
                            if (el) return el;
                        } catch(e) {}
                        try {
                            el = document.querySelector('[id="' + CSS.escape(c) + '"]');
                            if (el) return el;
                        } catch(e) {}
                    }
                    return null;
                }

                // 剔除注释文本中的返回箭头链接
                function getCleanNoteText(container) {
                    if (!container) return '';
                    var clone = container.cloneNode(true);
                    var links = clone.querySelectorAll('a');
                    for (var i = 0; i < links.length; i++) {
                        var t = (links[i].innerText || links[i].textContent || '').trim();
                        if (t.indexOf('↩') !== -1 || t.indexOf('↑') !== -1 || t.indexOf('返回') !== -1 || t.indexOf('back') !== -1) {
                            links[i].remove();
                        }
                    }
                    return (clone.innerText || clone.textContent || '').replace(/[↩↑]/g, '').trim();
                }

                // 智能向上查找或向后收集完整的注释说明文本
                // 彻底解决 Calibre 等排版中锚点只是 [1] 标签，而真正解释在外部 <p> 或后续节点的问题
                function extractTextForAnchor(el) {
                    if (!el) return '';

                    var blockTags = ['P', 'LI', 'DIV', 'ASIDE', 'DD', 'BLOCKQUOTE', 'SECTION', 'ARTICLE'];
                    var inlineTags = ['A', 'SPAN', 'SUP', 'SUB', 'FONT', 'EM', 'I', 'B', 'STRONG', 'SMALL'];

                    // 1. 向上寻找最近的注释包裹块级容器 (P, LI, ASIDE, DD, DIV)
                    var curr = el;
                    while (curr && curr.parentElement && curr.parentElement.tagName !== 'BODY' && curr.parentElement.tagName !== 'HTML') {
                        var parent = curr.parentElement;
                        var parentText = (parent.innerText || parent.textContent || '').trim();

                        // 如果父级是 <dt>，真正的注释内容在紧邻的 <dd> 中
                        if (parent.tagName === 'DT' && parent.nextElementSibling && parent.nextElementSibling.tagName === 'DD') {
                            var ddText = getCleanNoteText(parent.nextElementSibling);
                            if (ddText && ddText.length > 0) return ddText;
                        }

                        // 如果父级是块级容器
                        if (blockTags.indexOf(parent.tagName) !== -1) {
                            // 只要该容器长度合理（小于 2500 字符），且比内联锚点自身长，说明包含完整注释解释
                            if (parentText.length > 5 && parentText.length < 2500) {
                                var clean = getCleanNoteText(parent);
                                if (clean && clean.length > 5) {
                                    return clean;
                                }
                            }
                        }

                        if (inlineTags.indexOf(parent.tagName) === -1 && parent.tagName !== 'DIV') {
                            break;
                        }
                        curr = parent;
                    }

                    // 2. 检查紧随其后的块级兄弟元素（如 <a id="note1"></a><p>注释内容...</p>）
                    var next = el.nextElementSibling;
                    if (next && blockTags.indexOf(next.tagName) !== -1) {
                        var nextText = getCleanNoteText(next);
                        if (nextText && nextText.length > 0) return nextText;
                    }

                    // 3. 收集后续兄弟文本节点（处理单行连续排列的注释）
                    var collected = (el.innerText || el.textContent || '').trim();
                    var sib = el.nextSibling;
                    var step = 0;
                    while (sib && step < 15) {
                        step++;
                        if (sib.nodeType === 3) {
                            collected += sib.nodeValue;
                        } else if (sib.nodeType === 1) {
                            if (sib.tagName === 'A' && (sib.id || sib.name)) break;
                            if (sib.tagName === 'P' || sib.tagName === 'HR') break;
                            var sText = (sib.innerText || sib.textContent || '').trim();
                            if (sText.indexOf('↩') === -1 && sText.indexOf('↑') === -1) {
                                collected += ' ' + sText;
                            }
                        }
                        sib = sib.nextSibling;
                    }

                    var cleanCollected = collected.replace(/[↩↑]/g, '').trim();
                    if (cleanCollected.length > 3) {
                        return cleanCollected;
                    }

                    // 4. 兜底当前元素自身文本
                    return getCleanNoteText(el);
                }

                // 全局平滑跳转与高亮
                window.bookReaderScrollToAnchor = function(id) {
                    var el = findTargetElement(id);
                    if (el) {
                        el.scrollIntoView({ behavior: 'smooth', block: 'center' });
                        el.classList.remove('bookreader-anchor-highlight');
                        void el.offsetWidth;
                        el.classList.add('bookreader-anchor-highlight');
                    }
                };

                // 链接点击专属逻辑
                function handleLinkClick(link) {
                    var href = link.getAttribute('href');
                    if (!href || href === '#' || href.indexOf('javascript:') === 0) {
                        return;
                    }

                    var linkText = (link.innerText || link.textContent || '').trim();

                    // 外部网络链接
                    if (href.indexOf('http://') === 0 || href.indexOf('https://') === 0) {
                        if (window.AndroidEpub && window.AndroidEpub.openExternalUrl) {
                            window.AndroidEpub.openExternalUrl(href);
                        }
                        return;
                    }

                    var hashIdx = href.indexOf('#');
                    var targetId = hashIdx !== -1 ? href.substring(hashIdx + 1) : '';
                    var targetPath = hashIdx !== -1 ? href.substring(0, hashIdx) : href;

                    // 判断是否属于本章节内跳转
                    var isCurrentPage = false;
                    if (hashIdx === 0) {
                        isCurrentPage = true;
                    } else if (targetPath === '') {
                        isCurrentPage = true;
                    } else {
                        var currPath = window.location.pathname || '';
                        var currFile = currPath.split('/').pop();
                        if (currPath.endsWith(targetPath) || (currFile && targetPath.endsWith(currFile))) {
                            isCurrentPage = true;
                        }
                    }

                    if (isCurrentPage && targetId) {
                        // 回跳正文链接（如 ↩、↑、返回）
                        if (linkText.indexOf('↩') !== -1 || linkText.indexOf('↑') !== -1 || linkText.indexOf('返回') !== -1 || targetId.indexOf('ref') !== -1 || targetId.indexOf('back') !== -1) {
                            window.bookReaderScrollToAnchor(targetId);
                            return;
                        }

                        // 查找本页内的注释元素
                        var targetEl = findTargetElement(targetId);
                        if (targetEl) {
                            var noteText = extractTextForAnchor(targetEl);
                            var cleanNote = noteText.trim();
                            // 确保提取到的注释文本含有真正的解释正文（不仅是单纯的标号如 "[1]"）
                            if (cleanNote.length > 0 && cleanNote !== linkText && cleanNote.length > linkText.length) {
                                if (window.AndroidEpub && window.AndroidEpub.showFootnote) {
                                    window.AndroidEpub.showFootnote(linkText || '注释', cleanNote, targetId);
                                    return;
                                }
                            }
                            // 若未提取到更丰富的正文，直接平滑滚动定位至该元素并高亮
                            window.bookReaderScrollToAnchor(targetId);
                            return;
                        }
                    }

                    // 跨章节链接或外部文件注释
                    if (window.AndroidEpub && window.AndroidEpub.resolveAndShowExternalFootnote) {
                        window.AndroidEpub.resolveAndShowExternalFootnote(href, linkText);
                    }
                }

                // 延迟进度上报，避免未恢复滚动位置前被初始 0 进度覆盖
                var isScrollReportingEnabled = false;
                setTimeout(function() {
                    isScrollReportingEnabled = true;
                    reportPages();
                }, 400);

                window.onscroll = function() {
                    if (!isScrollReportingEnabled) return;
                    var total = getMaxScroll();
                    var current = getScrollTop();
                    var percent = total > 0 ? (current / total) : 0;
                    if (window.AndroidEpub) {
                        window.AndroidEpub.reportScroll(percent);
                    }
                    reportPages();
                };

                if (window.__brSelection) {
                    document.removeEventListener('selectionchange', window.__brSelection);
                }
                if (window.__brTouchStart) {
                    window.removeEventListener('touchstart', window.__brTouchStart);
                    window.removeEventListener('touchmove', window.__brTouchMove);
                    window.removeEventListener('touchend', window.__brTouchEnd);
                }
                if (window.__brClick) {
                    document.removeEventListener('click', window.__brClick, true);
                }

                window.__brSelection = function() {
                    try {
                        var s = window.getSelection().toString().trim();
                        if (s.length > 0 && s.length <= 120) {
                            if (window.AndroidEpub && window.AndroidEpub.onTextSelected) {
                                window.AndroidEpub.onTextSelected(s);
                            }
                        }
                    } catch(e) {}
                };
                document.addEventListener('selectionchange', window.__brSelection);

                var touchStartX = 0;
                var touchStartY = 0;
                var touchStartTime = 0;
                var isSwipe = false;
                var isScrollOrDrag = false;
                var justSwiped = false;

                window.__brTouchStart = function(e) {
                    if (e.touches && e.touches.length === 1) {
                        touchStartX = e.touches[0].clientX;
                        touchStartY = e.touches[0].clientY;
                        touchStartTime = Date.now();
                        isSwipe = false;
                        isScrollOrDrag = false;
                    }
                };
                window.__brTouchMove = function(e) {
                    if (e.touches && e.touches.length === 1) {
                        var dx = e.touches[0].clientX - touchStartX;
                        var dy = e.touches[0].clientY - touchStartY;
                        if (Math.abs(dx) > 12 || Math.abs(dy) > 12) {
                            isScrollOrDrag = true;
                        }
                        if (!window.__brPageMode && Math.abs(dx) > 30 && Math.abs(dx) > Math.abs(dy) * 1.5) {
                            isSwipe = true;
                        }
                    }
                };
                window.__brTouchEnd = function(e) {
                    if (e.changedTouches && e.changedTouches.length === 1 && isSwipe) {
                        var dx = e.changedTouches[0].clientX - touchStartX;
                        var dt = Date.now() - touchStartTime;
                        if (Math.abs(dx) > 40 && dt < 600) {
                            justSwiped = true;
                            setTimeout(function() { justSwiped = false; }, 350);
                            if (dx < 0) {
                                pageNext();
                            } else {
                                pagePrev();
                            }
                        }
                    }
                };
                window.addEventListener('touchstart', window.__brTouchStart, { passive: true });
                window.addEventListener('touchmove', window.__brTouchMove, { passive: true });
                window.addEventListener('touchend', window.__brTouchEnd, { passive: true });

                window.__brClick = function(e) {
                    if (justSwiped || isScrollOrDrag) {
                        isScrollOrDrag = false;
                        return;
                    }

                    var link = e.target ? (e.target.closest ? e.target.closest('a') : null) : null;
                    if (link) {
                        e.preventDefault();
                        e.stopPropagation();
                        handleLinkClick(link);
                        return;
                    }

                    var w = window.innerWidth;
                    var h = window.innerHeight;
                    var x = e.clientX;
                    var y = e.clientY;
                    if (y >= h * 0.82) {
                        if (window.AndroidEpub && window.AndroidEpub.onBottomEdgeTapped) {
                            window.AndroidEpub.onBottomEdgeTapped();
                        }
                        return;
                    }
                    var onLeft = x < w * 0.35;
                    var onRight = x >= w * 0.65;
                    if (!onLeft && !onRight) {
                        if (window.AndroidEpub) {
                            window.AndroidEpub.onScreenCenterTapped();
                        }
                        return;
                    }
                    e.preventDefault();
                    e.stopPropagation();
                    var forward = window.__brLeftHand ? onLeft : onRight;
                    if (window.AndroidEpub && window.AndroidEpub.onPageTurned) {
                        window.AndroidEpub.onPageTurned();
                    }
                    if (forward) {
                        pageNext();
                    } else {
                        pagePrev();
                    }
                };
                document.addEventListener('click', window.__brClick, true);

                // 滚动位置精准恢复逻辑
                var restoreFraction = $initialScrollFraction;
                if (restoreFraction > 0) {
                    function attemptRestore(retryCount) {
                        lineBoundsCache = null;
                        pageMapCache = null;
                        var max = getMaxScroll();
                        if (max > 10 || retryCount >= 5) {
                            var target = Math.round(max * restoreFraction);
                            if (window.__brPageMode) {
                                var starts = getPageStarts();
                                if (starts && starts.length > 0) {
                                    var idx = Math.min(starts.length - 1, Math.max(0, Math.round((starts.length - 1) * restoreFraction)));
                                    target = starts[idx];
                                }
                            }
                            setScroll(target, false);
                            scheduleFit();
                        } else if (retryCount < 6) {
                            setTimeout(function() {
                                attemptRestore(retryCount + 1);
                            }, 80);
                        }
                    }
                    attemptRestore(0);
                }
                scheduleFit();
            } catch(err) {
                console.error('BookReader Epub Error: ' + err);
            }
        })();
    """.trimIndent()

    webView.evaluateJavascript(js, null)
}

@SuppressLint("JavascriptInterface")
private fun WebView.registerEpubBridge(bridge: EpubJsBridge) {
    // EpubJsBridge methods are annotated with @JavascriptInterface; suppress AGP Lint UAST false positive on remember<T>.
    addJavascriptInterface(bridge, "AndroidEpub")
}

