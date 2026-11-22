package com.example.bookreader.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.bookreader.data.dictionary.DictionaryService
import com.example.bookreader.domain.model.DictionaryResult
import com.example.bookreader.ui.locale.LocalAppStrings
import com.example.bookreader.util.TtsManager
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DictionaryBottomSheet(
    initialQuery: String = "",
    onDismissRequest: () -> Unit,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
) {
    val context = LocalContext.current
    val strings = LocalAppStrings.current
    val coroutineScope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    var queryText by remember { mutableStateOf(initialQuery) }
    var isLoading by remember { mutableStateOf(false) }
    var currentResult by remember { mutableStateOf<DictionaryResult?>(null) }
    val historyList = remember { mutableStateListOf<String>() }

    val ttsManager = remember { TtsManager(context) }
    DisposableEffect(Unit) {
        onDispose {
            ttsManager.shutdown()
        }
    }

    fun performSearch(target: String) {
        val trimmed = target.trim()
        if (trimmed.isEmpty()) return
        focusManager.clearFocus()
        isLoading = true
        coroutineScope.launch {
            val res = DictionaryService.lookup(trimmed)
            currentResult = res
            isLoading = false
            if (!historyList.contains(trimmed)) {
                historyList.add(0, trimmed)
                if (historyList.size > 8) historyList.removeLast()
            }
        }
    }

    LaunchedEffect(initialQuery) {
        if (initialQuery.isNotBlank()) {
            queryText = initialQuery
            performSearch(initialQuery)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = null
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp)
                .heightIn(max = 600.dp)
        ) {
            // 顶端拖拽线
            Box(
                modifier = Modifier
                    .width(40.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f))
                    .align(Alignment.CenterHorizontally)
            )

            Spacer(modifier = Modifier.height(14.dp))

            // 搜索输入框
            OutlinedTextField(
                value = queryText,
                onValueChange = { queryText = it },
                placeholder = { Text(strings.dictionarySearchPlaceholder, fontSize = 14.sp) },
                singleLine = true,
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = strings.search,
                        tint = MaterialTheme.colorScheme.primary
                    )
                },
                trailingIcon = {
                    if (queryText.isNotEmpty()) {
                        IconButton(onClick = {
                            queryText = ""
                            currentResult = null
                        }) {
                            Icon(Icons.Default.Clear, contentDescription = strings.clearHistory)
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { performSearch(queryText) }),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            )

            // 历史快捷查询标签
            if (historyList.isNotEmpty() && currentResult == null && !isLoading) {
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(vertical = 4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.History,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("${strings.history}：", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                ) {
                    historyList.forEach { word ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                            modifier = Modifier.clickable {
                                queryText = word
                                performSearch(word)
                            }
                        ) {
                            Text(
                                text = word,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 内容显示区
            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(modifier = Modifier.size(36.dp), strokeWidth = 3.dp)
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(strings.searching, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else if (currentResult != null) {
                val res = currentResult!!
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                ) {
                    if (res.error != null) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                        ) {
                            Text(
                                text = res.error,
                                color = MaterialTheme.colorScheme.error,
                                fontSize = 13.sp,
                                modifier = Modifier.padding(14.dp)
                            )
                        }
                    } else {
                        // 词头行：词语、语种标识、发音与复制按钮
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = res.query,
                                    fontSize = 24.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )

                                Spacer(modifier = Modifier.width(8.dp))

                                Surface(
                                    color = if (res.isEnglish) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer,
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text(
                                        text = if (res.isEnglish) "英 ➔ 中" else "中文字典",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = if (res.isEnglish) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSecondaryContainer,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }

                            Row {
                                IconButton(
                                    onClick = { ttsManager.speak(res.query, res.isEnglish) }
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                                        contentDescription = "朗读发音",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }

                                IconButton(
                                    onClick = {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        val textToCopy = buildString {
                                            append(res.query)
                                            res.phonetic?.let { append(" $it\n") }
                                            res.definitions.forEach { d ->
                                                append(d.partOfSpeech?.let { "[$it] " } ?: "")
                                                append(d.meaning)
                                                append("\n")
                                            }
                                        }
                                        clipboard.setPrimaryClip(ClipData.newPlainText("词典释义", textToCopy))
                                        Toast.makeText(context, "释义已复制到剪贴板", Toast.LENGTH_SHORT).show()
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ContentCopy,
                                        contentDescription = "复制释义",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        // 音标 / 拼音
                        if (!res.phonetic.isNullOrEmpty()) {
                            Text(
                                text = res.phonetic,
                                fontSize = 15.sp,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 2.dp, bottom = 10.dp)
                            )
                        } else {
                            Spacer(modifier = Modifier.height(6.dp))
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        Spacer(modifier = Modifier.height(10.dp))

                        // 释义列表
                        if (res.definitions.isNotEmpty()) {
                            res.definitions.forEach { def ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.Top
                                ) {
                                    if (!def.partOfSpeech.isNullOrEmpty()) {
                                        Surface(
                                            color = MaterialTheme.colorScheme.surfaceVariant,
                                            shape = RoundedCornerShape(4.dp),
                                            modifier = Modifier.padding(end = 8.dp, top = 2.dp)
                                        ) {
                                            Text(
                                                text = def.partOfSpeech,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                            )
                                        }
                                    }

                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = def.meaning,
                                            fontSize = 14.sp,
                                            lineHeight = 20.sp,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )

                                        if (def.examples.isNotEmpty()) {
                                            Spacer(modifier = Modifier.height(4.dp))
                                            def.examples.forEach { ex ->
                                                Text(
                                                    text = "• $ex",
                                                    fontSize = 12.sp,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    lineHeight = 17.sp,
                                                    modifier = Modifier.padding(start = 4.dp, top = 2.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // 双语例句展示
                        if (res.examples.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "例句与用法参考",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.height(6.dp))

                            Card(
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    res.examples.forEachIndexed { index, ex ->
                                        if (index > 0) {
                                            HorizontalDivider(
                                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                                                modifier = Modifier.padding(vertical = 6.dp)
                                            )
                                        }
                                        Text(
                                            text = ex.original,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = ex.translation,
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(top = 2.dp)
                                        )
                                    }
                                }
                            }
                        }

                        // 词典数据源标注
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "权威来源：${res.source}",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            modifier = Modifier.align(Alignment.End)
                        )
                    }
                }
            } else {
                // 默认提示
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "支持英汉翻译、汉语字词、成语典故查询与真人离线发音",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}
