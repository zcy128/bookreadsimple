package com.example.bookreader.ui.auth

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.bookreader.R
import com.example.bookreader.data.auth.UserManager
import com.example.bookreader.ui.locale.AppLanguage
import com.example.bookreader.ui.locale.LocalAppLanguage
import com.example.bookreader.ui.locale.LocalAppStrings
import com.example.bookreader.ui.locale.LocaleManager
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.common.api.ApiException

@Composable
fun AuthScreen(
    userManager: UserManager,
    onLoginSuccess: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val strings = LocalAppStrings.current
    val currentLang = LocalAppLanguage.current

    var showLanguageMenu by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Google Sign-In Launcher
    val googleSignInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        isLoading = false
        val data = result.data
        if (result.resultCode == Activity.RESULT_CANCELED && data == null) {
            errorMessage = strings.cancelGoogleLogin
            return@rememberLauncherForActivityResult
        }
        val task = GoogleSignIn.getSignedInAccountFromIntent(data)
        try {
            val account = task.getResult(ApiException::class.java)
            if (account != null) {
                isLoading = true
                errorMessage = null
                val authResult = userManager.signInWithGoogle(account)
                isLoading = false
                if (authResult.isSuccess) {
                    onLoginSuccess()
                } else {
                    errorMessage = "Google 登录失败: ${authResult.exceptionOrNull()?.localizedMessage}"
                }
            } else {
                errorMessage = "未获取到 Google 账号"
            }
        } catch (e: ApiException) {
            val code = e.statusCode
            if (code == 12501 || code == 16) {
                errorMessage = strings.cancelGoogleLogin
                return@rememberLauncherForActivityResult
            }
            val detail = when (code) {
                10 -> "应用配置错误 (Code 10 DEVELOPER_ERROR: 请确保手机有网络且包名与SHA-1一致)"
                7 -> "网络不可达 (Code 7: 无法连接 Google 鉴权服务器，请检查网络/梯子)"
                12500 -> "Google Play 服务异常 (Code 12500: 请检查 OAuth 同意屏幕或 Google Play 服务)"
                else -> "Google 错误代码 $code: ${e.localizedMessage ?: "未完成登录"}"
            }
            errorMessage = detail
        } catch (e: Exception) {
            errorMessage = "登录异常: ${e.localizedMessage ?: e.message}"
        }
    }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // 右上角语言切换
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 40.dp, end = 16.dp)
            ) {
                IconButton(onClick = { showLanguageMenu = true }) {
                    Icon(
                        imageVector = Icons.Default.Language,
                        contentDescription = strings.languageSetting,
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                DropdownMenu(
                    expanded = showLanguageMenu,
                    onDismissRequest = { showLanguageMenu = false }
                ) {
                    AppLanguage.values().forEach { lang ->
                        val isSelected = currentLang == lang
                        DropdownMenuItem(
                            text = {
                                val label = when (lang) {
                                    AppLanguage.SYSTEM -> strings.languageSystem
                                    AppLanguage.ZH -> strings.languageZh
                                    AppLanguage.EN -> strings.languageEn
                                    AppLanguage.FR -> strings.languageFr
                                }
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = label,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                    )
                                    if (isSelected) {
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            },
                            onClick = {
                                LocaleManager.setLanguage(context, lang)
                                showLanguageMenu = false
                            }
                        )
                    }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .align(Alignment.Center)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                // 图标与标题
                Image(
                    painter = painterResource(id = R.mipmap.ic_launcher),
                    contentDescription = "BookReader",
                    modifier = Modifier
                        .size(80.dp)
                        .clip(RoundedCornerShape(16.dp))
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "BookReader",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = strings.appSubtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 4.dp, bottom = 24.dp)
                )

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // 1. Google 一键登录大按钮
                        Button(
                            onClick = {
                                errorMessage = null
                                isLoading = true
                                val client = userManager.getGoogleSignInClient(context)
                                googleSignInLauncher.launch(client.signInIntent)
                            },
                            enabled = !isLoading,
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp)
                        ) {
                            if (isLoading) {
                                CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                            } else {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = "G",
                                        fontWeight = FontWeight.Black,
                                        fontSize = 20.sp,
                                        color = Color(0xFF4285F4)
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(
                                        text = strings.signInWithGoogle,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 15.sp
                                    )
                                }
                            }
                        }

                        if (errorMessage != null) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Surface(
                                color = MaterialTheme.colorScheme.errorContainer,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = errorMessage ?: "",
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    fontSize = 12.sp,
                                    modifier = Modifier.padding(10.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
