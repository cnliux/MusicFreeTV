package com.tvmusic

import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.tvmusic.core.TvMusicApp
import com.tvmusic.player.PlayerManager
import com.tvmusic.ui.components.AppTitleBar
import com.tvmusic.ui.components.tvFocus
import com.tvmusic.ui.home.HomeScreen
import com.tvmusic.ui.home.HomeViewModel
import com.tvmusic.ui.player.PlayerScreen
import com.tvmusic.ui.recommend.RecommendScreen
import com.tvmusic.ui.recommend.RecommendViewModel
import com.tvmusic.ui.search.SearchScreen
import com.tvmusic.ui.search.SearchViewModel
import com.tvmusic.ui.setting.SettingsScreen
import com.tvmusic.ui.setting.SettingsViewModel
import com.tvmusic.ui.sheet.SheetScreen
import com.tvmusic.ui.sheet.SheetTarget
import com.tvmusic.ui.sheet.SheetViewModel
import com.tvmusic.ui.toplist.TopListScreen
import com.tvmusic.ui.toplist.TopListViewModel

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            com.tvmusic.ui.theme.MusicFreeTheme {
                App()
            }
        }
    }

    /** 调试：确认遥控器按键是否到达 Activity（logcat -s DpadDebug）。 */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.action == android.view.KeyEvent.ACTION_DOWN) {
            android.util.Log.d(
                "DpadDebug",
                "key=${event.keyCode} (${android.view.KeyEvent.keyCodeToString(event.keyCode)}) repeat=${event.repeatCount}"
            )
        }
        return super.dispatchKeyEvent(event)
    }

    @Composable
    fun App() {
        val navController = rememberNavController()
        val context = LocalContext.current
        val app = TvMusicApp.from(context)
        val uiState by PlayerManager.uiState.collectAsState()

        val needed = navController.currentBackStackEntryAsState().value
        val route = needed?.destination?.route
        val tabKey = when {
            route == "search" -> "search"
            route == "settings" -> "settings"
            route == "about" -> "about"
            else -> "home"
        }

        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            AppTitleBar(
                selected = if (route == "sheet" || route == "player") "" else tabKey,
                onSelect = { key ->
                    when (key) {
                        "home" -> navController.navigate("home") {
                            popUpTo(navController.graph.startDestinationId) { inclusive = true }
                        }
                        "search" -> navController.navigate("search")
                        "settings" -> navController.navigate("settings")
                        "about" -> navController.navigate("about")
                    }
                }
            )

            Box(Modifier.weight(1f).fillMaxWidth()) {
                NavHost(navController, startDestination = "home") {
                    composable("home") {
                        val vm = com.tvmusic.ui.home.rememberVm { HomeViewModel(app) }
                        HomeScreen(
                            viewModel = vm,
                            onOpenDetail = { target ->
                                SheetTarget.value = target
                                navController.navigate("sheet")
                            },
                            onOpenRecommend = { platform ->
                                navController.navigate(
                                    if (platform != null) {
                                        "recommend?platform=${Uri.encode(platform)}"
                                    } else "recommend"
                                )
                            },
                            onOpenTopList = { platform ->
                                navController.navigate(
                                    if (platform != null) {
                                        "toplist?platform=${Uri.encode(platform)}"
                                    } else "toplist"
                                )
                            },
                            onOpenPlayer = { navController.navigate("player") },
                            onOpenMyList = { navController.navigate("mylist") }
                        )
                    }
                    composable("search") {
                        val vm = com.tvmusic.ui.home.rememberVm { SearchViewModel(app) }
                        SearchScreen(
                            viewModel = vm,
                            onOpenDetail = { target ->
                                SheetTarget.value = target
                                navController.navigate("sheet")
                            }
                        )
                    }
                    composable("sheet") {
                        val vm = com.tvmusic.ui.home.rememberVm { SheetViewModel(app) }
                        SheetScreen(vm, onBack = { navController.popBackStack() })
                    }
                    composable(
                        route = "recommend?platform={platform}",
                        arguments = listOf(
                            navArgument("platform") {
                                type = NavType.StringType
                                nullable = true
                                defaultValue = null
                            }
                        )
                    ) { backStackEntry ->
                        val platform = backStackEntry.arguments?.getString("platform")
                        val vm = com.tvmusic.ui.home.rememberVm {
                            RecommendViewModel(app, platform)
                        }
                        RecommendScreen(
                            viewModel = vm,
                            onBack = { navController.popBackStack() },
                            onOpenDetail = { target ->
                                SheetTarget.value = target
                                navController.navigate("sheet")
                            }
                        )
                    }
                    composable(
                        route = "toplist?platform={platform}",
                        arguments = listOf(
                            navArgument("platform") {
                                type = NavType.StringType
                                nullable = true
                                defaultValue = null
                            }
                        )
                    ) { backStackEntry ->
                        val platform = backStackEntry.arguments?.getString("platform")
                        val vm = com.tvmusic.ui.home.rememberVm {
                            TopListViewModel(app, platform)
                        }
                        TopListScreen(
                            viewModel = vm,
                            onBack = { navController.popBackStack() },
                            onOpenDetail = { target ->
                                SheetTarget.value = target
                                navController.navigate("sheet")
                            }
                        )
                    }
                    composable("player") {
                        PlayerScreen(onBack = { navController.popBackStack() })
                    }
                    composable("mylist") {
                        com.tvmusic.ui.mylist.MyListScreen(
                            playback = app.playback,
                            onBack = { navController.popBackStack() }
                        )
                    }
                    composable("settings") {
                        val vm = com.tvmusic.ui.home.rememberVm { SettingsViewModel(app) }
                        SettingsScreen(viewModel = vm)
                    }
                    composable("about") {
                        com.tvmusic.ui.about.AboutScreen()
                    }
                }
            }

            // 迷你播放条
            if (route != "player" && uiState.current != null) {
                MiniPlayerBar(
                    entry = uiState.current!!,
                    isPlaying = uiState.isPlaying,
                    positionMs = uiState.positionMs,
                    durationMs = uiState.durationMs,
                    lyricLine = uiState.lrcLines.getOrNull(uiState.lrcIndex)?.text ?: "",
                    onPrev = { PlayerManager.prev() },
                    onToggle = { PlayerManager.playPause() },
                    onNext = { PlayerManager.next() },
                    onClick = { navController.navigate("player") }
                )
            }
        }
    }
}

@Composable
private fun MiniPlayerBar(
    entry: com.tvmusic.player.QueueEntry,
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    lyricLine: String,
    onPrev: () -> Unit,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onClick: () -> Unit
) {
    val primary = MaterialTheme.colorScheme.primary
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                androidx.compose.ui.graphics.Brush.verticalGradient(
                    listOf(androidx.compose.ui.graphics.Color(0xF214181F), androidx.compose.ui.graphics.Color(0xFA0B0D12))
                ),
                RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
            )
    ) {
        // 顶部细进度条
        androidx.compose.material3.LinearProgressIndicator(
            progress = { if (durationMs > 0) positionMs.toFloat() / durationMs else 0f },
            modifier = Modifier.fillMaxWidth().height(3.dp),
            color = primary,
            trackColor = androidx.compose.ui.graphics.Color(0x22FFFFFF)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 左：封面（点击进入播放器）
            com.tvmusic.ui.components.Artwork(
                entry.artwork,
                Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .tvFocus(1.04f)
                    .clickable(onClick = onClick)
            )

            // 中：歌名 + 当前歌词行（整体居中）
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = entry.title.ifBlank { "未知歌曲" },
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 16.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = lyricLine.ifBlank {
                        if (isPlaying) entry.artist.ifBlank { "正在播放" } else "已暂停"
                    },
                    color = if (lyricLine.isNotBlank()) primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // 右：操作按钮
            MiniControl("⏮", primary, onPrev)
            // 播放/暂停（主色圆形，更突出）
            Box(
                modifier = Modifier
                    .padding(horizontal = 6.dp)
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(primary)
                    .tvFocus(1.08f)
                    .clickable(onClick = onToggle),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (isPlaying) "⏸" else "▶",
                    color = MaterialTheme.colorScheme.onPrimary,
                    fontSize = 20.sp
                )
            }
            // 下一曲
            MiniControl("⏭", primary, onNext)
        }
    }
}

@Composable
private fun MiniControl(symbol: String, accent: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(androidx.compose.ui.graphics.Color(0x14FFFFFF))
            .tvFocus(1.1f)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(symbol, color = MaterialTheme.colorScheme.onSurface, fontSize = 18.sp)
    }
}
