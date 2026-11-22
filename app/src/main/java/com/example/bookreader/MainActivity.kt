package com.example.bookreader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.bookreader.ui.auth.AuthScreen
import com.example.bookreader.ui.bookshelf.BookshelfScreen
import com.example.bookreader.ui.bookshelf.BookshelfViewModel
import com.example.bookreader.ui.components.AdaptiveNavigationScaffold
import com.example.bookreader.ui.components.AppDestination
import com.example.bookreader.ui.locale.LocalAppLanguage
import com.example.bookreader.ui.locale.LocalAppStrings
import com.example.bookreader.ui.locale.LocaleManager
import com.example.bookreader.ui.reader.ReaderContainerScreen
import com.example.bookreader.ui.reader.ReaderViewModel
import com.example.bookreader.ui.settings.SyncScreen
import com.example.bookreader.ui.theme.BookReaderTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val app = application as BookReaderApplication
        val repository = app.repository

        setContent {
            val currentLanguage by LocaleManager.currentLanguage.collectAsState()
            val strings = LocaleManager.getStrings(currentLanguage)

            CompositionLocalProvider(
                LocalAppStrings provides strings,
                LocalAppLanguage provides currentLanguage
            ) {
                BookReaderTheme {
                    Surface(modifier = Modifier.fillMaxSize()) {
                    val currentUser by repository.userManager.currentUser.collectAsState()

                    // 1. 用户必须登录：未登录时强制显示登录认证页
                    if (!currentUser.isLoggedIn) {
                        AuthScreen(
                            userManager = repository.userManager,
                            onLoginSuccess = {
                                // 状态已响应式更新，自动进入书架
                            }
                        )
                    } else {
                        // 2. 已登录：进入专属书架与阅读系统
                        val configuration = LocalConfiguration.current
                        val isTablet = configuration.screenWidthDp >= 600

                        val navController = rememberNavController()
                        var currentDestination by remember { mutableStateOf(AppDestination.BOOKSHELF) }

                        NavHost(
                            navController = navController,
                            startDestination = "main"
                        ) {
                            composable("main") {
                                val bookshelfViewModel: BookshelfViewModel = viewModel(
                                    factory = BookshelfViewModel.Factory(repository)
                                )

                                AdaptiveNavigationScaffold(
                                    isTablet = isTablet,
                                    currentDestination = currentDestination,
                                    onDestinationSelected = { dest ->
                                        currentDestination = dest
                                        if (dest == AppDestination.OFFLINE) {
                                            bookshelfViewModel.toggleOnlyDownloaded(true)
                                        } else if (dest == AppDestination.BOOKSHELF) {
                                            bookshelfViewModel.toggleOnlyDownloaded(false)
                                        }
                                    }
                                ) {
                                    when (currentDestination) {
                                        AppDestination.BOOKSHELF, AppDestination.OFFLINE -> {
                                            BookshelfScreen(
                                                viewModel = bookshelfViewModel,
                                                isTablet = isTablet,
                                                onBookClick = { book ->
                                                    navController.navigate("reader/${book.id}")
                                                }
                                            )
                                        }
                                        AppDestination.SYNC -> {
                                            SyncScreen(
                                                repository = repository,
                                                isTablet = isTablet
                                            )
                                        }
                                    }
                                }
                            }

                            composable(
                                route = "reader/{bookId}",
                                arguments = listOf(navArgument("bookId") { type = NavType.StringType })
                            ) { backStackEntry ->
                                val bookId = backStackEntry.arguments?.getString("bookId") ?: ""
                                val readerViewModel: ReaderViewModel = viewModel(
                                    factory = ReaderViewModel.Factory(
                                        bookId = bookId,
                                        repository = repository,
                                        cacheBaseDir = cacheDir
                                    )
                                )

                                ReaderContainerScreen(
                                    viewModel = readerViewModel,
                                    isTablet = isTablet,
                                    onBack = { navController.popBackStack() }
                                )
                            }
                        }
                    }
                }
            }
        }
        }
    }
}
