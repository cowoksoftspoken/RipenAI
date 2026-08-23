package com.ripenai

import android.os.Bundle
import androidx.compose.animation.Crossfade
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Eco
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Icon
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ripenai.ui.RipenViewModel
import com.ripenai.ui.screens.AppMode
import com.ripenai.ui.screens.FarmerModeScreen
import com.ripenai.ui.screens.ConsumerSettingsScreen
import com.ripenai.ui.screens.HistoryScreen
import com.ripenai.ui.screens.ModeSelectorScreen
import com.ripenai.ui.screens.QuestionsScreen
import com.ripenai.ui.screens.ResultScreen
import com.ripenai.ui.screens.ScanScreen
import com.ripenai.ui.theme.AgriPrimary
import com.ripenai.ui.theme.AgriPrimaryContainer
import com.ripenai.ui.theme.MyApplicationTheme
import kotlinx.coroutines.delay

enum class NavigationTab { SCAN, HISTORY, SETTINGS }

class MainActivity : ComponentActivity() {
    private val viewModel: RipenViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                RipenApp(viewModel)
            }
        }
    }
}

@Composable
private fun RipenApp(viewModel: RipenViewModel) {
    var mode by remember { mutableStateOf<AppMode?>(null) }
    var showSplash by remember { mutableStateOf(true) }
    var phraseIndex by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        repeat(3) {
            delay(520)
            phraseIndex += 1
        }
        showSplash = false
    }
    if (showSplash) {
        FriendlySplash(phraseIndex)
    } else {
        when (val selectedMode = mode) {
            null -> ModeSelectorScreen(onModeSelected = { mode = it })
            AppMode.FARMER -> {
                DisposableEffect(Unit) {
                    viewModel.startPolling()
                    onDispose { viewModel.stopPolling() }
                }
                FarmerModeScreen(viewModel = viewModel, onBack = { mode = null })
            }
            AppMode.CONSUMER -> ConsumerApp(viewModel = viewModel, onSwitchMode = { viewModel.resetScan(); mode = null })
        }
    }
}

@Composable
private fun FriendlySplash(phraseIndex: Int) {
    val phrases = listOf("Kita pilih buahnya pelan-pelan.", "Lihat warna dan teksturnya bersama.", "Sebentar, kita pastikan hasilnya.")
    Box(modifier = Modifier.fillMaxSize().background(Color.White), contentAlignment = androidx.compose.ui.Alignment.Center) {
        Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(28.dp)) {
            Box(contentAlignment = androidx.compose.ui.Alignment.Center, modifier = Modifier.background(AgriPrimaryContainer, androidx.compose.foundation.shape.CircleShape).padding(22.dp)) {
                Icon(Icons.Default.Eco, contentDescription = null, tint = AgriPrimary, modifier = Modifier.padding(4.dp))
            }
            Text("RipenAI", color = AgriPrimary, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Crossfade(targetState = phraseIndex.coerceIn(0, phrases.lastIndex), label = "splash_phrase") { index ->
                Text(phrases[index], color = Color(0xFF334155), fontSize = 16.sp, textAlign = TextAlign.Center)
            }
            CircularProgressIndicator(color = AgriPrimary, strokeWidth = 2.dp, modifier = Modifier.padding(top = 12.dp).then(Modifier))
        }
    }
}

@Composable
private fun ConsumerApp(viewModel: RipenViewModel, onSwitchMode: () -> Unit) {
    var currentTab by remember { mutableStateOf(NavigationTab.SCAN) }
    val scanResult by viewModel.scanResult.collectAsState()
    val questions by viewModel.questionResponse.collectAsState()
    val loadingQuestions by viewModel.isLoadingQuestions.collectAsState()

    val showFullFlow = scanResult != null || questions != null || loadingQuestions
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = Color.White,
        // Nested screens (for example ScanScreen) own their status-bar inset.
        // Keeping the parent inset at zero prevents the header from being
        // shifted down by the same status bar twice.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (!showFullFlow) {
                NavigationBar(
                    containerColor = Color.White,
                    tonalElevation = 8.dp,
                    windowInsets = WindowInsets.navigationBars,
                    modifier = Modifier.testTag("bottom_nav_bar")
                ) {
                    NavigationBarItem(
                        selected = currentTab == NavigationTab.SCAN,
                        onClick = { currentTab = NavigationTab.SCAN },
                        label = { Text("Pindai", fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                        icon = { Icon(Icons.Default.CenterFocusStrong, contentDescription = "Pindai") },
                        colors = navColors(),
                        modifier = Modifier.testTag("nav_scan_item")
                    )
                    NavigationBarItem(
                        selected = currentTab == NavigationTab.HISTORY,
                        onClick = { currentTab = NavigationTab.HISTORY },
                        label = { Text("Riwayat", fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                        icon = { Icon(Icons.Default.History, contentDescription = "Riwayat") },
                        colors = navColors(),
                        modifier = Modifier.testTag("nav_history_item")
                    )
                    NavigationBarItem(
                        selected = currentTab == NavigationTab.SETTINGS,
                        onClick = { currentTab = NavigationTab.SETTINGS },
                        label = { Text("Pengaturan", fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                        icon = { Icon(Icons.Default.Settings, contentDescription = "Pengaturan") },
                        colors = navColors(),
                        modifier = Modifier.testTag("nav_settings_item")
                    )
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding).background(Color.White)) {
            when {
                loadingQuestions || questions != null -> QuestionsScreen(viewModel, onBackToScan = viewModel::resetScan)
                scanResult != null -> ResultScreen(viewModel, onBackToScan = viewModel::resetScan)
                currentTab == NavigationTab.SCAN -> ScanScreen(
                    viewModel = viewModel,
                    onNavigateToSettings = { currentTab = NavigationTab.SETTINGS },
                    onSwitchMode = onSwitchMode
                )
                currentTab == NavigationTab.HISTORY -> HistoryScreen(viewModel, onNavigateToResult = { currentTab = NavigationTab.SCAN })
                currentTab == NavigationTab.SETTINGS -> ConsumerSettingsScreen(viewModel)
            }
        }
    }
}

@Composable
private fun navColors() = NavigationBarItemDefaults.colors(
    selectedIconColor = AgriPrimary,
    selectedTextColor = AgriPrimary,
    indicatorColor = AgriPrimaryContainer,
    unselectedIconColor = Color.Gray,
    unselectedTextColor = Color.Gray
)
