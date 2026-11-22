package com.example.bookreader.ui.settings

import java.util.Locale

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.bookreader.data.repository.BookRepository
import com.example.bookreader.ui.locale.LocalAppStrings
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncScreen(
    repository: BookRepository,
    isTablet: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val strings = LocalAppStrings.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val userProfile by repository.userManager.currentUser.collectAsState()
    var driveAuthorized by remember(userProfile.userId) {
        mutableStateOf(repository.hasDriveAuthorization(userProfile.userId))
    }
    var driveFolderName by remember(userProfile.userId) {
        mutableStateOf(repository.driveFolderName(userProfile.userId))
    }

    val driveFolderLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = com.example.bookreader.data.remote.DriveFolderPicker()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                val name = repository.authorizeDriveFolder(uri)
                driveAuthorized = true
                driveFolderName = name
                repository.syncPendingProgress()
                snackbarHostState.showSnackbar(strings.cloudStoragePath(name))
            } catch (e: com.example.bookreader.data.remote.DriveRootNotAllowedException) {
                snackbarHostState.showSnackbar("请选择 Google Drive 中的子文件夹，不要选择根目录")
            } catch (e: Exception) {
                snackbarHostState.showSnackbar(e.localizedMessage ?: "授权失败")
            }
        }
    }

    val googleSignInLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val task = com.google.android.gms.auth.api.signin.GoogleSignIn.getSignedInAccountFromIntent(result.data)
        try {
            val account = task.getResult(com.google.android.gms.common.api.ApiException::class.java)
            if (account != null) {
                val res = repository.userManager.signInWithGoogle(account)
                scope.launch {
                    if (res.isSuccess) {
                        snackbarHostState.showSnackbar("已成功通过 Google 账号登录")
                    } else {
                        snackbarHostState.showSnackbar("Google 登录失败: ${res.exceptionOrNull()?.message}")
                    }
                }
            }
        } catch (e: Exception) {
            // 用户取消
        }
    }

    val downloadedBooks by repository.downloadedBooks.collectAsState(initial = emptyList())
    var isSyncing by remember { mutableStateOf(false) }

    val userBooksDir = remember(userProfile.userId) {
        File(context.filesDir, "users/${userProfile.userId}/books")
    }
    val cacheSizeMb = remember(downloadedBooks, userProfile.userId) {
        val totalBytes = userBooksDir.listFiles()?.sumOf { it.length() } ?: 0L
        String.format(Locale.US, "%.2f MB", totalBytes.toDouble() / (1024 * 1024))
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(strings.syncSettingsTitle, fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { padding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 当前用户专属账户卡片
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.AccountCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "当前读者：${userProfile.displayName}",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Text(
                                text = "User UID: ${userProfile.userId}",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Google Drive 授权由你自己在系统选择器里完成，程序里没有写死权限：\n" +
                                "• 选择 Google Drive 中的一个子文件夹，不要选根目录\n" +
                                "• 之后放入或删除 epub / kepub / pdf / txt，应用会自动扫描\n" +
                                "• 阅读进度保存在这个文件夹的 bookreader-progress.json",
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                    )

                    Spacer(modifier = Modifier.height(12.dp))
                    if (userProfile.isLoggedIn) {
                        OutlinedButton(
                            onClick = {
                                repository.userManager.signOut(context)
                                scope.launch {
                                    snackbarHostState.showSnackbar("已退出 Google 登录")
                                }
                            }
                        ) {
                            Text("退出 Google 登录", color = MaterialTheme.colorScheme.error)
                        }
                    } else {
                        Button(
                            onClick = {
                                val client = repository.userManager.getGoogleSignInClient(context)
                                googleSignInLauncher.launch(client.signInIntent)
                            }
                        ) {
                            Icon(Icons.Default.AccountCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("使用 Google 账号登录")
                        }
                    }
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (driveAuthorized) Color(0xFFE8F5E9) else Color(0xFFFFF3E0)
                )
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (driveAuthorized) Icons.Default.CloudDone else Icons.Default.CloudOff,
                        contentDescription = null,
                        tint = if (driveAuthorized) Color(0xFF2E7D32) else Color(0xFFE65100),
                        modifier = Modifier.size(36.dp)
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (driveAuthorized) strings.connected else strings.driveAuthorizationNeeded,
                            fontWeight = FontWeight.Bold,
                            color = if (driveAuthorized) Color(0xFF1B5E20) else Color(0xFFBF360C)
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = driveFolderName?.let(strings.cloudStoragePath) ?: strings.progressSavedOnDevice,
                            fontSize = 12.sp,
                            color = Color.DarkGray
                        )
                    }
                }
            }

            // 专属离线存储管理
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Storage, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("当前读者本地离线空间", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    Text("• 当前读者专属离线书籍: ${downloadedBooks.size} 本", style = MaterialTheme.typography.bodyMedium)
                    Text("• 当前读者专属空间占用: $cacheSizeMb", style = MaterialTheme.typography.bodyMedium)
                    Text("• 本地隔离目录: files/users/${userProfile.userId}/books/", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

                    Spacer(modifier = Modifier.height(16.dp))

                    val folderLabel = driveFolderName
                    if (driveAuthorized && !folderLabel.isNullOrBlank()) {
                        Text(
                            text = strings.cloudStoragePath(folderLabel),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Text(
                            text = strings.driveAuthorizationNeeded,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = { driveFolderLauncher.launch(null) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(strings.authorizeDriveFolder, maxLines = 1)
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = {
                            scope.launch {
                                isSyncing = true
                                try {
                                    repository.syncPendingProgress()
                                    snackbarHostState.showSnackbar("已扫描已授权的 Google Drive 文件夹")
                                } finally {
                                    isSyncing = false
                                }
                            }
                        },
                        enabled = !isSyncing && driveAuthorized,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (isSyncing) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                        } else {
                            Icon(Icons.Default.CloudSync, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text(strings.syncNow, maxLines = 1)
                    }

                    if (driveAuthorized) {
                        TextButton(onClick = { driveFolderLauncher.launch(null) }) {
                            Text(strings.changeDriveFolder)
                        }
                    }
                }
            }
        }
    }
}
