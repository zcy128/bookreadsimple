package com.example.bookreader.ui.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.automirrored.filled.NavigateBefore
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.bookreader.domain.model.BookFormat
import com.example.bookreader.domain.model.ChapterProgressBarMode
import com.example.bookreader.domain.model.PageTurnHand
import com.example.bookreader.domain.model.ReaderBgTheme
import com.example.bookreader.domain.model.ReadingMode
import com.example.bookreader.ui.components.DictionaryBottomSheet
import com.example.bookreader.ui.locale.AppLanguage
import com.example.bookreader.ui.locale.LocalAppLanguage
import com.example.bookreader.ui.locale.LocalAppStrings
import com.example.bookreader.ui.locale.LocaleManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderContainerScreen(
    viewModel: ReaderViewModel,
    isTablet: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val strings = LocalAppStrings.current
    val currentLang = LocalAppLanguage.current

    val uiState by viewModel.uiState.collectAsState()
    val tocSheetState = rememberModalBottomSheetState()
    val settingsSheetState = rememberModalBottomSheetState()

    var showDictionary by remember { mutableStateOf(false) }
    var dictionaryQuery by remember { mutableStateOf("") }
    var selectedWordPill by remember { mutableStateOf<String?>(null) }
    val showChapterBar = uiState.chapterProgressBarMode != ChapterProgressBarMode.HIDDEN &&
        !uiState.isControlsVisible
    val systemInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout)
    val pageColor = if (uiState.book?.format == BookFormat.PDF) {
        Color(0xFF2E2E2E)
    } else {
        Color(uiState.settings.bgTheme.bgHex)
    }
    val onPage = if (pageColor.luminance() < 0.5f) Color(0xFFE8E0D4) else Color(0xFF241C14)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(pageColor)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(systemInsets)
        ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clipToBounds()
                .padding(bottom = 4.dp)
        ) {
        when (val state = uiState.contentState) {
            is ReaderContentState.Loading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(strings.preparingBook, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            is ReaderContentState.Error -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(text = state.message, color = MaterialTheme.colorScheme.error)
                }
            }
            is ReaderContentState.TxtReady -> {
                TxtReaderView(
                    parsedResult = state.result,
                    settings = uiState.settings,
                    isTablet = isTablet,
                    currentChapterIndex = uiState.currentChapterIndex,
                    initialCharOffset = uiState.progress.charOffset,
                    reserveBottomBar = false,
                    onProgressChanged = { percent, charOffset, chapterIdx, chapterFraction, pageIndex, pageCount ->
                        viewModel.onTxtProgressChanged(percent, charOffset, chapterIdx, chapterFraction, pageIndex, pageCount)
                    },
                    onToggleControls = { viewModel.toggleControls() },
                    onBottomEdgeTap = { viewModel.onBottomEdgeTapped() },
                    onPageTurn = { viewModel.hideControls() },
                    onNextChapter = { viewModel.nextChapter() },
                    onPreviousChapter = { viewModel.previousChapterFromGesture() }
                )
            }
            is ReaderContentState.PdfReady -> {
                PdfReaderView(
                    pdfFile = state.pdfFile,
                    isTablet = isTablet,
                    initialPage = uiState.progress.currentPage,
                    readingMode = uiState.settings.readingMode,
                    pageTurnHand = uiState.settings.pageTurnHand,
                    showPageBadge = false,
                    onProgressChanged = { percent, current, total ->
                        viewModel.onPdfProgressChanged(percent, current, total)
                    },
                    onToggleControls = { viewModel.toggleControls() },
                    onBottomEdgeTap = { viewModel.onBottomEdgeTapped() },
                    onPageTurn = { viewModel.hideControls() }
                )
            }
            is ReaderContentState.EpubReady -> {
                EpubReaderView(
                    epubBook = state.epubBook,
                    currentChapterIndex = uiState.currentChapterIndex,
                    initialScrollFraction = uiState.initialChapterScrollFraction,
                    readerSettings = uiState.settings,
                    isTablet = isTablet,
                    onChapterProgressChanged = { percent ->
                        viewModel.onEpubChapterProgressChanged(percent)
                    },
                    onChapterPagesChanged = { index, count ->
                        viewModel.onChapterPagesChanged(index, count)
                    },
                    onToggleControls = { viewModel.toggleControls() },
                    onPageTurn = { viewModel.hideControls() },
                    onNextChapter = { viewModel.nextChapter() },
                    onPreviousChapter = { viewModel.previousChapterFromGesture() },
                    onSelectChapter = { viewModel.selectChapter(it) },
                    onBottomEdgeTap = { viewModel.onBottomEdgeTapped() },
                    onLookupWord = { word -> selectedWordPill = word }
                )
            }
        }
        }
        val bookPercent = (uiState.progress.progressPercent * 100).toInt().coerceIn(0, 100)
        val pageCount = uiState.chapterPageCount.coerceAtLeast(1)
        val pageIndex = uiState.chapterPageIndex.coerceIn(0, pageCount - 1)
        val pagesLeft = (pageCount - pageIndex).coerceAtLeast(1)
        val footerTextColor = if (pageColor.luminance() < 0.5f) Color(0xFFBDBDBD) else Color(0xFF8A8A8A)
        val footerStyle = TextStyle(
            fontSize = 11.sp,
            lineHeight = 13.sp,
            platformStyle = PlatformTextStyle(includeFontPadding = false),
            color = footerTextColor
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(22.dp)
                .background(Color.Transparent)
                .clickable { viewModel.onBottomEdgeTapped() },
            contentAlignment = Alignment.Center
        ) {
            if (showChapterBar) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = strings.chapterPagesLeft(pagesLeft),
                        style = footerStyle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "$bookPercent%",
                        style = footerStyle,
                        maxLines = 1
                    )
                }
            }
        }
        }

        // 顶部悬浮控制栏
        AnimatedVisibility(
            visible = uiState.isControlsVisible,
            enter = slideInVertically(initialOffsetY = { -it }),
            exit = slideOutVertically(targetOffsetY = { -it }),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            Surface(
                color = pageColor.copy(alpha = 0.92f),
                shadowElevation = 0.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.systemBars)
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onBack) {
                        Text(strings.back, color = onPage, fontSize = 14.sp)
                    }
                    Text(
                        text = uiState.book?.title ?: strings.appTitle,
                        color = onPage,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = {
                        dictionaryQuery = selectedWordPill ?: ""
                        showDictionary = true
                    }) {
                        Text(strings.dictionary, color = onPage.copy(alpha = 0.72f), fontSize = 14.sp)
                    }
                    if (uiState.chapters.isNotEmpty()) {
                        TextButton(onClick = { viewModel.setTocOpen(true) }) {
                            Text(strings.tableOfContents, color = onPage.copy(alpha = 0.72f), fontSize = 14.sp)
                        }
                    }
                }
            }
        }

        // 底部悬浮控制与进度栏
        AnimatedVisibility(
            visible = uiState.isControlsVisible,
            enter = slideInVertically(initialOffsetY = { it }),
            exit = slideOutVertically(targetOffsetY = { it }),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Surface(
                color = pageColor.copy(alpha = 0.92f),
                shadowElevation = 0.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = { viewModel.previousChapter() },
                        enabled = uiState.chapters.isNotEmpty() && uiState.currentChapterIndex > 0
                    ) {
                        Text(strings.previousChapter, color = onPage, fontSize = 13.sp)
                    }
                    TextButton(onClick = { viewModel.setSettingsOpen(true) }) {
                        Text(strings.readingPreferences, color = onPage, fontSize = 13.sp)
                    }
                    TextButton(
                        onClick = { viewModel.nextChapter() },
                        enabled = uiState.chapters.isNotEmpty() && uiState.currentChapterIndex < uiState.chapters.lastIndex
                    ) {
                        Text(strings.nextChapter, color = onPage, fontSize = 13.sp)
                    }
                }
            }
        }

        // 划词快捷查词悬浮胶囊
        AnimatedVisibility(
            visible = selectedWordPill != null && !showDictionary,
            enter = fadeIn() + slideInVertically(initialOffsetY = { it / 2 }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 2 }),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = if (uiState.isControlsVisible) 110.dp else 40.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                shadowElevation = 6.dp,
                modifier = Modifier.clickable {
                    dictionaryQuery = selectedWordPill ?: ""
                    showDictionary = true
                    selectedWordPill = null
                }
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Icon(
                        Icons.Default.Translate,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "${strings.lookupInDictionary}：“${selectedWordPill?.take(12)}”",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    IconButton(
                        onClick = { selectedWordPill = null },
                        modifier = Modifier.size(20.dp)
                    ) {
                        Icon(
                            Icons.Default.Clear,
                            contentDescription = "关闭",
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }
        }
    }

    // 目录 BottomSheet
    if (uiState.isTocOpen) {
        ModalBottomSheet(
            onDismissRequest = { viewModel.setTocOpen(false) },
            sheetState = tocSheetState
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Text(
                    text = "${strings.tableOfContents} (${uiState.chapters.size})",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                LazyColumn(modifier = Modifier.fillMaxWidth().height(360.dp)) {
                    itemsIndexed(
                        items = uiState.chapters,
                        key = { idx, chapter -> "${idx}_${chapter.contentPath}_${chapter.startCharOffset}" },
                        contentType = { _, _ -> "chapter" }
                    ) { idx, chapter ->
                        val isSelected = idx == uiState.currentChapterIndex
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                                .clickable { viewModel.selectChapter(idx) }
                                .padding(horizontal = 12.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = chapter.title,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }
    }

    // 排版与主题设置面板
    if (uiState.isSettingsOpen) {
        ModalBottomSheet(
            onDismissRequest = { viewModel.setSettingsOpen(false) },
            sheetState = settingsSheetState
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
                Text(
                    text = strings.readingPreferences,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(16.dp))

                // 字号调节
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(strings.fontSize(uiState.settings.fontSizeSp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = {
                                val newSp = (uiState.settings.fontSizeSp - 2).coerceAtLeast(12)
                                viewModel.updateSettings(uiState.settings.copy(fontSizeSp = newSp))
                            }
                        ) {
                            Text("A-", fontWeight = FontWeight.Bold)
                        }
                        IconButton(
                            onClick = {
                                val newSp = (uiState.settings.fontSizeSp + 2).coerceAtMost(32)
                                viewModel.updateSettings(uiState.settings.copy(fontSizeSp = newSp))
                            }
                        ) {
                            Text("A+", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(strings.readingMode, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                Spacer(modifier = Modifier.height(8.dp))
                Row {
                    PaperChoice(
                        label = strings.readingModeScroll,
                        selected = uiState.settings.readingMode == ReadingMode.SCROLL,
                        onClick = { viewModel.updateSettings(uiState.settings.copy(readingMode = ReadingMode.SCROLL)) }
                    )
                    PaperChoice(
                        label = strings.readingModePage,
                        selected = uiState.settings.readingMode == ReadingMode.PAGE,
                        onClick = { viewModel.updateSettings(uiState.settings.copy(readingMode = ReadingMode.PAGE)) }
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = strings.readingModePageDesc,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(strings.pageTurnHand, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                Spacer(modifier = Modifier.height(8.dp))
                Row {
                    PaperChoice(
                        label = strings.pageTurnLeft,
                        selected = uiState.settings.pageTurnHand == PageTurnHand.LEFT,
                        onClick = { viewModel.updateSettings(uiState.settings.copy(pageTurnHand = PageTurnHand.LEFT)) }
                    )
                    PaperChoice(
                        label = strings.pageTurnRight,
                        selected = uiState.settings.pageTurnHand == PageTurnHand.RIGHT,
                        onClick = { viewModel.updateSettings(uiState.settings.copy(pageTurnHand = PageTurnHand.RIGHT)) }
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = if (uiState.settings.pageTurnHand == PageTurnHand.LEFT) {
                        strings.pageTurnLeftDesc
                    } else {
                        strings.pageTurnRightDesc
                    },
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = strings.progressBarHint,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(16.dp))

                // 背景色与主题选择
                Text(strings.themeAndColor, style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    ReaderBgTheme.values().forEach { theme ->
                        val isSelected = uiState.settings.bgTheme == theme
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { viewModel.updateSettings(uiState.settings.copy(bgTheme = theme)) }
                                .padding(4.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(Color(theme.bgHex))
                                    .border(
                                        width = if (isSelected) 3.dp else 1.dp,
                                        color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Gray.copy(alpha = 0.5f),
                                        shape = CircleShape
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "文",
                                    color = Color(theme.textHex),
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(text = theme.displayName, fontSize = 11.sp)
                        }
                    }
                }

                if (isTablet && uiState.settings.readingMode == ReadingMode.SCROLL) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(strings.tabletTwoPage, fontWeight = FontWeight.Medium)
                            Text(strings.tabletTwoPageDesc, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = uiState.settings.twoPageModeForTablet,
                            onCheckedChange = { checked ->
                                viewModel.updateSettings(uiState.settings.copy(twoPageModeForTablet = checked))
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 界面语言设置
                Text(strings.languageSetting, style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AppLanguage.values().forEach { lang ->
                        val isSelected = currentLang == lang
                        val label = when (lang) {
                            AppLanguage.SYSTEM -> strings.languageSystem
                            AppLanguage.ZH -> strings.languageZh
                            AppLanguage.EN -> strings.languageEn
                            AppLanguage.FR -> strings.languageFr
                        }
                        FilterChip(
                            selected = isSelected,
                            onClick = { LocaleManager.setLanguage(context, lang) },
                            label = { Text(label, fontSize = 12.sp) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))
            }
        }
    }

    // 词典与翻译弹窗
    if (showDictionary) {
        DictionaryBottomSheet(
            initialQuery = dictionaryQuery,
            onDismissRequest = {
                showDictionary = false
                dictionaryQuery = ""
            }
        )
    }
}

@Composable
private fun PaperChoice(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Text(
        text = label,
        color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 16.sp,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(end = 22.dp, bottom = 6.dp)
            .drawBehind {
                if (selected) {
                    drawLine(
                        color = Color(0xFF241C14),
                        start = Offset(0f, size.height),
                        end = Offset(size.width, size.height),
                        strokeWidth = 2f
                    )
                }
            }
    )
}
