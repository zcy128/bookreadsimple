package com.example.bookreader.ui.bookshelf

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.bookreader.data.remote.DriveFolderPicker
import com.example.bookreader.domain.model.Book
import com.example.bookreader.domain.model.BookFormat
import com.example.bookreader.ui.components.BookCard
import com.example.bookreader.ui.components.BookListItem
import com.example.bookreader.ui.components.DictionaryBottomSheet
import com.example.bookreader.ui.locale.AppLanguage
import com.example.bookreader.ui.locale.LocalAppLanguage
import com.example.bookreader.ui.locale.LocalAppStrings
import com.example.bookreader.ui.locale.LocaleManager
import com.example.bookreader.ui.theme.Ink

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookshelfScreen(
    viewModel: BookshelfViewModel,
    isTablet: Boolean,
    onBookClick: (Book) -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    val strings = LocalAppStrings.current
    val currentLang = LocalAppLanguage.current
    var showLanguageMenu by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }

    LaunchedEffect(uiState.syncMessage) {
        uiState.syncMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearSyncMessage()
        }
    }

    var showAccountDialog by remember { mutableStateOf(false) }
    var bookToDelete by remember { mutableStateOf<Book?>(null) }
    var bookToRename by remember { mutableStateOf<Book?>(null) }
    var renameText by remember { mutableStateOf("") }
    var bookToTag by remember { mutableStateOf<Book?>(null) }
    var tagText by remember { mutableStateOf("") }
    var pendingImportUri by remember { mutableStateOf<Uri?>(null) }
    var pendingImportName by remember { mutableStateOf("") }
    var importTagText by remember { mutableStateOf("") }
    var passwordBook by remember { mutableStateOf<Book?>(null) }
    var passwordMode by remember { mutableStateOf(BookPasswordMode.UNLOCK) }
    var showPasswordDialog by remember { mutableStateOf(false) }
    var passwordText by remember { mutableStateOf("") }
    var confirmPasswordText by remember { mutableStateOf("") }
    var passwordError by remember { mutableStateOf<String?>(null) }
    var showDictionary by remember { mutableStateOf(false) }

    fun openBook(book: Book) {
        if (book.passwordProtected) {
            passwordBook = book
            passwordMode = BookPasswordMode.UNLOCK
            passwordText = ""
            confirmPasswordText = ""
            passwordError = null
            showPasswordDialog = true
        } else {
            viewModel.recordBookAccess(book)
            onBookClick(book)
        }
    }

    fun requestPasswordAction(book: Book) {
        if (book.passwordProtected) {
            passwordBook = book
            passwordMode = BookPasswordMode.REMOVE
            passwordText = ""
            confirmPasswordText = ""
            passwordError = null
            showPasswordDialog = true
        } else if (viewModel.hasBookPassword()) {
            viewModel.protectBook(book)
        } else {
            passwordBook = book
            passwordMode = BookPasswordMode.SET
            passwordText = ""
            confirmPasswordText = ""
            passwordError = null
            showPasswordDialog = true
        }
    }

    // 启动时自动尝试静默恢复已登录的 Google 账号
    LaunchedEffect(Unit) {
        viewModel.trySilentSignIn(context)
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.onAppResumed()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Google 登录 Launcher（带完善的错误捕获与提示，彻底避免无反应）
    val googleSignInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_CANCELED) {
            viewModel.postMessage(strings.cancelGoogleLogin)
            return@rememberLauncherForActivityResult
        }
        val task = com.google.android.gms.auth.api.signin.GoogleSignIn.getSignedInAccountFromIntent(result.data)
        try {
            val account = task.getResult(com.google.android.gms.common.api.ApiException::class.java)
            if (account != null) {
                viewModel.signInWithGoogle(account)
            } else {
                viewModel.postMessage("未获取到 Google 账号")
            }
        } catch (e: com.google.android.gms.common.api.ApiException) {
            val code = e.statusCode
            val reason = when (code) {
                10 -> "配置错误 (Code 10: 签名指纹或客户端ID需刷新)"
                7 -> "网络错误 (Code 7: 无法连接至 Google 服务器，请检查网络)"
                12500 -> "登录异常 (Code 12500: 请确认设备 Google Play 框架正常)"
                else -> "错误代码 $code: ${e.localizedMessage ?: "登录未完成"}"
            }
            viewModel.postMessage("Google 登录未成功: $reason")
        } catch (e: Exception) {
            viewModel.postMessage("登录遇到异常: ${e.localizedMessage ?: e.message}")
        }
    }

    val driveFolderLauncher = rememberLauncherForActivityResult(
        contract = DriveFolderPicker()
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.authorizeDriveFolder(uri)
        }
    }

    // 文件选择器 (支持 epub, pdf, txt)
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            var fileName = "imported_book.txt"
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && nameIndex != -1) {
                    fileName = cursor.getString(nameIndex)
                }
            }
            pendingImportName = fileName
            importTagText = ""
            pendingImportUri = uri
        }
    }

    if (showAccountDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showAccountDialog = false },
            title = { Text(if (uiState.currentUser.isLoggedIn) strings.googleAccountTitle else strings.loginAccountTitle) },
            text = {
                Column {
                    if (uiState.currentUser.isLoggedIn) {
                        Text(strings.currentReader(uiState.currentUser.displayName), fontWeight = FontWeight.Bold)
                        uiState.currentUser.email?.let { Text(strings.emailLabel(it), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(strings.userIdLabel(uiState.currentUser.userId), fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = uiState.driveFolderName?.let(strings.cloudStoragePath)
                                ?: strings.driveAuthorizationNeeded,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(strings.cloudStorageTip, fontSize = 12.sp)
                        Spacer(modifier = Modifier.height(8.dp))
                        androidx.compose.material3.TextButton(onClick = { driveFolderLauncher.launch(null) }) {
                            Text(if (uiState.needsDriveAuthorization) strings.authorizeDriveFolder else strings.changeDriveFolder)
                        }
                    } else {
                        Text(strings.guestStatusDesc)
                    }
                }
            },
            confirmButton = {
                if (uiState.currentUser.isLoggedIn) {
                    androidx.compose.material3.TextButton(
                        onClick = {
                            viewModel.signOutGoogle(context)
                            showAccountDialog = false
                        }
                    ) {
                        Text(strings.signOut, color = MaterialTheme.colorScheme.error)
                    }
                } else {
                    androidx.compose.material3.Button(
                        onClick = {
                            showAccountDialog = false
                            val client = viewModel.getGoogleSignInClient(context)
                            googleSignInLauncher.launch(client.signInIntent)
                        }
                    ) {
                        Text(strings.selectGoogleAccount)
                    }
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showAccountDialog = false }) {
                    Text(strings.cancel)
                }
            }
        )
    }

    if (bookToDelete != null) {
        val target = bookToDelete!!
        AlertDialog(
            onDismissRequest = { bookToDelete = null },
            icon = {
                Icon(
                    imageVector = Icons.Default.DeleteOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = { Text(strings.deleteBookTitle) },
            text = { Text(strings.deleteBookConfirm(target.title)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteBook(target)
                        bookToDelete = null
                    }
                ) {
                    Text(strings.delete, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { bookToDelete = null }) {
                    Text(strings.cancel)
                }
            }
        )
    }

    val importUri = pendingImportUri
    if (importUri != null) {
        AlertDialog(
            onDismissRequest = { pendingImportUri = null },
            title = { Text(strings.addBookTitle) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(pendingImportName, style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(
                        value = importTagText,
                        onValueChange = { importTagText = it },
                        label = { Text(strings.bookTagLabel) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.importBook(importUri, pendingImportName, importTagText)
                        pendingImportUri = null
                    }
                ) {
                    Text(strings.confirmAction)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingImportUri = null }) {
                    Text(strings.cancel)
                }
            }
        )
    }

    if (bookToTag != null) {
        AlertDialog(
            onDismissRequest = { bookToTag = null },
            title = { Text(strings.editTag) },
            text = {
                OutlinedTextField(
                    value = tagText,
                    onValueChange = { tagText = it },
                    label = { Text(strings.bookTagLabel) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val target = bookToTag
                        if (target != null) viewModel.updateBookTag(target, tagText)
                        bookToTag = null
                    }
                ) {
                    Text(strings.confirmAction)
                }
            },
            dismissButton = {
                TextButton(onClick = { bookToTag = null }) {
                    Text(strings.cancel)
                }
            }
        )
    }

    if (bookToRename != null) {
        AlertDialog(
            onDismissRequest = { bookToRename = null },
            title = { Text(strings.renameBookTitle) },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text(strings.bookTitleLabel) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val target = bookToRename
                        if (target != null) viewModel.renameBook(target, renameText)
                        bookToRename = null
                    },
                    enabled = renameText.trim().isNotEmpty()
                ) {
                    Text(strings.confirmAction)
                }
            },
            dismissButton = {
                TextButton(onClick = { bookToRename = null }) {
                    Text(strings.cancel)
                }
            }
        )
    }

    if (showPasswordDialog && passwordBook != null) {
        val settingPassword = passwordMode == BookPasswordMode.SET
        AlertDialog(
            onDismissRequest = { showPasswordDialog = false },
            title = {
                Text(
                    when (passwordMode) {
                        BookPasswordMode.SET -> strings.setBookPasswordTitle
                        BookPasswordMode.REMOVE -> strings.removeProtectionTitle
                        BookPasswordMode.UNLOCK -> strings.enterBookPasswordTitle
                    }
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = passwordText,
                        onValueChange = {
                            passwordText = it
                            passwordError = null
                        },
                        label = { Text(strings.bookPasswordLabel) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (settingPassword) {
                        OutlinedTextField(
                            value = confirmPasswordText,
                            onValueChange = {
                                confirmPasswordText = it
                                passwordError = null
                            },
                            label = { Text(strings.confirmBookPasswordLabel) },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    if (passwordError != null) {
                        Text(
                            text = passwordError ?: "",
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 12.sp
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val target = passwordBook ?: return@TextButton
                        when (passwordMode) {
                            BookPasswordMode.SET -> {
                                when {
                                    passwordText.length < 4 -> passwordError = strings.passwordTooShort
                                    passwordText != confirmPasswordText -> passwordError = strings.passwordMismatch
                                    else -> {
                                        viewModel.setSharedPasswordAndProtect(target, passwordText)
                                        passwordText = ""
                                        confirmPasswordText = ""
                                        showPasswordDialog = false
                                    }
                                }
                            }
                            BookPasswordMode.UNLOCK -> {
                                if (viewModel.verifyBookPassword(passwordText)) {
                                    passwordText = ""
                                    showPasswordDialog = false
                                    viewModel.recordBookAccess(target)
                                    onBookClick(target)
                                } else {
                                    passwordError = strings.wrongBookPassword
                                }
                            }
                            BookPasswordMode.REMOVE -> {
                                if (viewModel.verifyBookPassword(passwordText)) {
                                    viewModel.unprotectBook(target)
                                    passwordText = ""
                                    showPasswordDialog = false
                                } else {
                                    passwordError = strings.wrongBookPassword
                                }
                            }
                        }
                    }
                ) {
                    Text(strings.confirmAction)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    passwordText = ""
                    confirmPasswordText = ""
                    showPasswordDialog = false
                }) {
                    Text(strings.cancel)
                }
            }
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 22.dp, end = 8.dp, top = 8.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "BookReader",
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Serif,
                        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = strings.appTitle,
                        fontSize = 32.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Serif
                    )
                }
                TextButton(onClick = {
                    filePickerLauncher.launch(
                        arrayOf("application/epub+zip", "application/x-mobipocket-ebook", "application/pdf", "text/plain", "*/*")
                    )
                }) {
                    Text(strings.importShort, color = MaterialTheme.colorScheme.onSurface)
                }
                TextButton(onClick = { viewModel.toggleViewMode() }) {
                    Text(
                        text = if (uiState.isListView) strings.viewGrid else strings.viewList,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Box {
                    TextButton(onClick = { showMoreMenu = true }) {
                        Text(strings.moreActions, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    DropdownMenu(expanded = showMoreMenu, onDismissRequest = { showMoreMenu = false }) {
                        DropdownMenuItem(
                            text = { Text(strings.dictionary) },
                            onClick = {
                                showMoreMenu = false
                                showDictionary = true
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(if (uiState.isSyncing) strings.syncing else strings.syncProgress) },
                            onClick = {
                                showMoreMenu = false
                                if (!uiState.isSyncing) viewModel.syncCloudProgress()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(strings.languageSetting) },
                            onClick = {
                                showMoreMenu = false
                                showLanguageMenu = true
                            }
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    if (uiState.currentUser.isLoggedIn) {
                                        uiState.currentUser.displayName.substringBefore(" ").ifBlank { uiState.currentUser.displayName }
                                    } else {
                                        strings.signInWithGoogle
                                    }
                                )
                            },
                            onClick = {
                                showMoreMenu = false
                                if (uiState.currentUser.isLoggedIn) {
                                    showAccountDialog = true
                                } else {
                                    val client = viewModel.getGoogleSignInClient(context)
                                    googleSignInLauncher.launch(client.signInIntent)
                                }
                            }
                        )
                    }
                    DropdownMenu(expanded = showLanguageMenu, onDismissRequest = { showLanguageMenu = false }) {
                        AppLanguage.values().forEach { lang ->
                            val isSelected = currentLang == lang
                            val label = when (lang) {
                                AppLanguage.SYSTEM -> strings.languageSystem
                                AppLanguage.ZH -> strings.languageZh
                                AppLanguage.EN -> strings.languageEn
                                AppLanguage.FR -> strings.languageFr
                            }
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = label,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                    )
                                },
                                onClick = {
                                    LocaleManager.setLanguage(context, lang)
                                    showLanguageMenu = false
                                }
                            )
                        }
                    }
                }
            }

            BasicTextField(
                value = uiState.searchQuery,
                onValueChange = { viewModel.onSearchQueryChanged(it) },
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 16.sp
                ),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.onSurface),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 22.dp, vertical = 12.dp),
                decorationBox = { inner ->
                    Column {
                        Box {
                            if (uiState.searchQuery.isEmpty()) {
                                Text(
                                    text = strings.searchPlaceholder,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 16.sp
                                )
                            }
                            inner()
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(MaterialTheme.colorScheme.outline)
                        )
                    }
                }
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 22.dp),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ShelfFilter(
                    label = strings.filterAll,
                    selected = uiState.selectedFormat == null && !uiState.onlyDownloaded,
                    onClick = {
                        viewModel.onFormatFilterSelected(null)
                        viewModel.toggleOnlyDownloaded(false)
                    }
                )
                BookFormat.values().forEach { format ->
                    ShelfFilter(
                        label = format.displayName,
                        selected = uiState.selectedFormat == format,
                        onClick = { viewModel.onFormatFilterSelected(format) }
                    )
                }
                ShelfFilter(
                    label = strings.filterDownloadedOnly,
                    selected = uiState.onlyDownloaded,
                    onClick = { viewModel.toggleOnlyDownloaded(!uiState.onlyDownloaded) }
                )
            }

            if (uiState.needsDriveAuthorization) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(strings.driveAuthorizationNeeded, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(strings.cloudStorageTip, fontSize = 13.sp)
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(onClick = { driveFolderLauncher.launch(null) }) {
                            Text(strings.authorizeDriveFolder)
                        }
                    }
                }
            }

            AnimatedVisibility(visible = uiState.lastSyncSummary != null) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = uiState.lastSyncSummary.orEmpty(),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { viewModel.clearLastSyncSummary() }) {
                            Text("关闭", fontSize = 12.sp)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 自适应书籍网格（手机双列，平板 3-6 列）
            val minCellSize = if (isTablet) 200.dp else 160.dp

            val currentId = uiState.books.firstOrNull { it.lastReadTime > 0L }?.id
            val pullToRefreshState = rememberPullToRefreshState()
            LaunchedEffect(pullToRefreshState.isRefreshing) {
                if (pullToRefreshState.isRefreshing) {
                    viewModel.syncCloudProgress()
                    if (!uiState.isSyncing) {
                        pullToRefreshState.endRefresh()
                    }
                }
            }
            LaunchedEffect(uiState.isSyncing) {
                if (!uiState.isSyncing) {
                    pullToRefreshState.endRefresh()
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .nestedScroll(pullToRefreshState.nestedScrollConnection)
            ) {
            if (uiState.books.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = if (uiState.currentUser.isLoggedIn) strings.emptyBookshelfTitle else strings.welcomeTitle,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = when {
                                uiState.needsDriveAuthorization -> strings.driveAuthorizationNeeded
                                uiState.currentUser.isLoggedIn -> strings.emptyBookshelfDescLoggedIn
                                else -> strings.welcomeDesc
                            },
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else if (uiState.isListView) {
                LazyColumn(
                    contentPadding = PaddingValues(bottom = 24.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(uiState.books, key = { it.id }, contentType = { "book" }) { book ->
                        PrioritySwipeRow(
                            book = book,
                            onClearPriority = { viewModel.clearBookPriority(book) }
                        ) {
                            BookListItem(
                                book = book,
                                downloadProgress = uiState.downloadProgressMap[book.id],
                                isCurrent = book.id == currentId,
                                onClick = { openBook(book) },
                                onDownloadClick = { viewModel.downloadBook(book) },
                                onRenameClick = {
                                    renameText = book.title
                                    bookToRename = book
                                },
                                onTagClick = {
                                    tagText = book.tag
                                    bookToTag = book
                                },
                                onPasswordClick = { requestPasswordAction(book) },
                                onDeleteClick = { bookToDelete = book },
                                onClearPriority = { viewModel.clearBookPriority(book) }
                            )
                        }
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = minCellSize),
                    contentPadding = PaddingValues(18.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(uiState.books, key = { it.id }, contentType = { "book" }) { book ->
                        BookCard(
                            book = book,
                            downloadProgress = uiState.downloadProgressMap[book.id],
                            isCurrent = book.id == currentId,
                            onClick = { openBook(book) },
                            onDownloadClick = { viewModel.downloadBook(book) },
                            onRenameClick = {
                                renameText = book.title
                                bookToRename = book
                            },
                            onTagClick = {
                                tagText = book.tag
                                bookToTag = book
                            },
                            onPasswordClick = { requestPasswordAction(book) },
                            onDeleteClick = { bookToDelete = book }
                        )
                    }
                }
            }
            PullToRefreshContainer(
                state = pullToRefreshState,
                modifier = Modifier.align(Alignment.TopCenter)
            )
            }
        }
    }

    if (showDictionary) {
        DictionaryBottomSheet(
            initialQuery = "",
            onDismissRequest = { showDictionary = false }
        )
    }
}

private enum class BookPasswordMode {
    SET,
    UNLOCK,
    REMOVE
}

@Composable
private fun ShelfFilter(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Text(
        text = label,
        color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 13.sp,
        maxLines = 1,
        softWrap = false,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(bottom = 4.dp)
            .drawBehind {
                if (selected) {
                    drawLine(
                        color = Ink,
                        start = Offset(0f, size.height),
                        end = Offset(size.width, size.height),
                        strokeWidth = 1.5f
                    )
                }
            }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PrioritySwipeRow(
    book: Book,
    onClearPriority: () -> Unit,
    content: @Composable () -> Unit
) {
    val strings = LocalAppStrings.current
    if (book.lastReadTime <= 0L) {
        content()
        return
    }
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                onClearPriority()
            }
            false
        }
    )
    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                Text(
                    text = strings.clearReadingPriority,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Medium
                )
            }
        },
        content = {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .background(MaterialTheme.colorScheme.background)
            ) {
                content()
            }
        }
    )
}

