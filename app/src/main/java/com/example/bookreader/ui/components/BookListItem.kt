package com.example.bookreader.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.bookreader.domain.model.Book
import com.example.bookreader.ui.locale.LocalAppStrings

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BookListItem(
    book: Book,
    downloadProgress: Float?,
    isCurrent: Boolean,
    onClick: () -> Unit,
    onDownloadClick: () -> Unit,
    onRenameClick: () -> Unit,
    onTagClick: () -> Unit,
    onPasswordClick: () -> Unit,
    onDeleteClick: () -> Unit,
    modifier: Modifier = Modifier,
    onClearPriority: (() -> Unit)? = null
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val strings = LocalAppStrings.current
    val ink = MaterialTheme.colorScheme.onSurface
    val soft = MaterialTheme.colorScheme.onSurfaceVariant
    val rule = MaterialTheme.colorScheme.outline
    val meta = bookMeta(book, strings.encryptedMark)

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onDeleteClick
                )
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .padding(start = 12.dp)
                    .width(3.dp)
                    .height(36.dp)
                    .background(if (isCurrent) ink else rule.copy(alpha = 0f))
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp, end = 8.dp)
            ) {
                Text(
                    text = book.title,
                    color = ink,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = meta,
                    color = soft,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 3.dp)
                )
                if (downloadProgress != null) {
                    Box(
                        modifier = Modifier
                            .padding(top = 8.dp)
                            .fillMaxWidth()
                            .height(2.dp)
                            .background(rule)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(downloadProgress.coerceIn(0f, 1f))
                                .fillMaxHeight()
                                .background(ink)
                        )
                    }
                } else if (book.progressPercent > 0f) {
                    Box(
                        modifier = Modifier
                            .padding(top = 8.dp)
                            .fillMaxWidth()
                            .height(2.dp)
                            .background(rule)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(book.progressPercent.coerceIn(0f, 1f))
                                .fillMaxHeight()
                                .background(ink)
                        )
                    }
                }
                if (!book.isDownloaded && downloadProgress == null) {
                    Text(
                        text = strings.notDownloaded,
                        color = soft,
                        fontSize = 12.sp,
                        modifier = Modifier
                            .padding(top = 4.dp)
                            .clickable(onClick = onDownloadClick)
                    )
                }
            }
            Box {
                Text(
                    text = "···",
                    color = soft,
                    fontSize = 18.sp,
                    modifier = Modifier
                        .clickable { menuExpanded = true }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                )
                BookActionMenu(
                    expanded = menuExpanded,
                    passwordProtected = book.passwordProtected,
                    showClearPriority = book.lastReadTime > 0L && onClearPriority != null,
                    onDismiss = { menuExpanded = false },
                    onRenameClick = onRenameClick,
                    onTagClick = onTagClick,
                    onPasswordClick = onPasswordClick,
                    onDeleteClick = onDeleteClick,
                    onClearPriority = { onClearPriority?.invoke() }
                )
            }
        }
        Box(
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .fillMaxWidth()
                .height(1.dp)
                .background(rule)
        )
    }
}

@Composable
internal fun BookActionMenu(
    expanded: Boolean,
    passwordProtected: Boolean,
    showClearPriority: Boolean = false,
    onDismiss: () -> Unit,
    onRenameClick: () -> Unit,
    onTagClick: () -> Unit,
    onPasswordClick: () -> Unit,
    onDeleteClick: () -> Unit,
    onClearPriority: () -> Unit = {}
) {
    val strings = LocalAppStrings.current
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        if (showClearPriority) {
            DropdownMenuItem(
                text = { Text(strings.clearReadingPriority) },
                onClick = {
                    onDismiss()
                    onClearPriority()
                }
            )
        }
        DropdownMenuItem(
            text = { Text(strings.renameBook) },
            onClick = {
                onDismiss()
                onRenameClick()
            }
        )
        DropdownMenuItem(
            text = { Text(strings.editTag) },
            onClick = {
                onDismiss()
                onTagClick()
            }
        )
        DropdownMenuItem(
            text = {
                Text(if (passwordProtected) strings.removePasswordProtection else strings.passwordProtect)
            },
            onClick = {
                onDismiss()
                onPasswordClick()
            }
        )
        DropdownMenuItem(
            text = { Text(strings.deleteBookTitle) },
            onClick = {
                onDismiss()
                onDeleteClick()
            }
        )
    }
}

internal fun bookMeta(book: Book, encryptedMark: String): String {
    val parts = mutableListOf(book.author)
    if (book.tag.isNotBlank()) parts += book.tag
    if (book.passwordProtected) parts += encryptedMark
    return parts.joinToString("  ·  ")
}
