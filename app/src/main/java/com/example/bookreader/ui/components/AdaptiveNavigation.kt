package com.example.bookreader.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.CollectionsBookmark
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocalLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.example.bookreader.ui.locale.LocalAppStrings

enum class AppDestination(val route: String, val title: String, val icon: ImageVector) {
    BOOKSHELF("bookshelf", "书架", Icons.Default.LocalLibrary),
    OFFLINE("offline", "离线已存", Icons.Default.CollectionsBookmark),
    SYNC("sync", "云端同步", Icons.Default.CloudSync)
}

@Composable
fun AdaptiveNavigationScaffold(
    isTablet: Boolean,
    currentDestination: AppDestination,
    onDestinationSelected: (AppDestination) -> Unit,
    content: @Composable () -> Unit
) {
    val strings = LocalAppStrings.current
    val getTitle: (AppDestination) -> String = { dest ->
        when (dest) {
            AppDestination.BOOKSHELF -> strings.navBookshelf
            AppDestination.OFFLINE -> strings.navOffline
            AppDestination.SYNC -> strings.navSync
        }
    }

    if (isTablet) {
        // 平板与大屏：左侧 NavigationRail
        Row(modifier = Modifier.fillMaxSize()) {
            NavigationRail(
                modifier = Modifier.fillMaxHeight(),
                header = {
                    Spacer(modifier = Modifier.height(16.dp))
                    Icon(
                        imageVector = Icons.Default.LocalLibrary,
                        contentDescription = "BookReader",
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
            ) {
                AppDestination.values().forEach { destination ->
                    val title = getTitle(destination)
                    NavigationRailItem(
                        selected = currentDestination == destination,
                        onClick = { onDestinationSelected(destination) },
                        icon = { Icon(destination.icon, contentDescription = title) },
                        label = { Text(title) }
                    )
                }
            }

            // 主内容区域
            androidx.compose.foundation.layout.Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                content()
            }
        }
    } else {
        // 手机屏幕：底部 NavigationBar
        Scaffold(
            bottomBar = {
                NavigationBar {
                    AppDestination.values().forEach { destination ->
                        val title = getTitle(destination)
                        NavigationBarItem(
                            selected = currentDestination == destination,
                            onClick = { onDestinationSelected(destination) },
                            icon = { Icon(destination.icon, contentDescription = title) },
                            label = { Text(title) }
                        )
                    }
                }
            }
        ) { paddingValues ->
            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                content()
            }
        }
    }
}
