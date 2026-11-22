package com.example.bookreader.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.bookreader.domain.model.Book
import com.example.bookreader.ui.locale.LocalAppStrings

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BookCard(
    book: Book,
    downloadProgress: Float?,
    isCurrent: Boolean,
    onClick: () -> Unit,
    onDownloadClick: () -> Unit,
    onRenameClick: () -> Unit,
    onTagClick: () -> Unit,
    onPasswordClick: () -> Unit,
    onDeleteClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val strings = LocalAppStrings.current
    val ink = MaterialTheme.colorScheme.onSurface
    val paper = MaterialTheme.colorScheme.background
    val soft = MaterialTheme.colorScheme.onSurfaceVariant
    val rule = MaterialTheme.colorScheme.outline
    val cover = if (isCurrent) ink else MaterialTheme.colorScheme.surfaceVariant
    val onCover = if (isCurrent) paper else ink
    val progress = if (downloadProgress != null) downloadProgress else book.progressPercent

    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(148.dp)
                .background(cover)
                .combinedClickable(onClick = onClick, onLongClick = onDeleteClick)
                .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 10.dp)
        ) {
            Text(
                text = book.format.displayName,
                color = onCover.copy(alpha = 0.72f),
                fontFamily = FontFamily.Serif,
                fontSize = 11.sp,
                letterSpacing = 1.4.sp
            )
            Column(modifier = Modifier.align(Alignment.BottomStart)) {
                Text(
                    text = book.title,
                    color = onCover,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = book.author,
                    color = onCover.copy(alpha = 0.72f),
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
        Text(
            text = listOfNotNull(
                book.tag.takeIf { it.isNotBlank() },
                strings.encryptedMark.takeIf { book.passwordProtected }
            ).joinToString("  ·  ").ifBlank { " " },
            color = soft,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp)
        )
        Box(
            modifier = Modifier
                .padding(top = 4.dp)
                .fillMaxWidth()
                .height(2.dp)
                .background(rule)
        ) {
            if (progress > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress.coerceIn(0f, 1f))
                        .fillMaxHeight()
                        .background(ink)
                )
            }
        }
        Box {
            Text(
                text = "···",
                color = soft,
                fontSize = 16.sp,
                modifier = Modifier
                    .clickable { menuExpanded = true }
                    .padding(top = 2.dp, bottom = 4.dp)
            )
            BookActionMenu(
                expanded = menuExpanded,
                passwordProtected = book.passwordProtected,
                onDismiss = { menuExpanded = false },
                onRenameClick = onRenameClick,
                onTagClick = onTagClick,
                onPasswordClick = onPasswordClick,
                onDeleteClick = onDeleteClick
            )
        }
        if (!book.isDownloaded && downloadProgress == null) {
            Text(
                text = strings.notDownloaded,
                color = soft,
                fontSize = 11.sp,
                modifier = Modifier.clickable(onClick = onDownloadClick)
            )
        }
    }
}
