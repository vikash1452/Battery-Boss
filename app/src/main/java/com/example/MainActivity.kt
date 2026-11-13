package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.DeveloperBoard
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.outlined.DeveloperBoard
import androidx.compose.material.icons.outlined.HealthAndSafety
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.screens.BatteryHealthScreen
import com.example.ui.screens.DashboardScreen
import com.example.ui.screens.HardwareAndOemScreen
import com.example.ui.screens.HistoryAndAboutScreen
import com.example.ui.screens.VoiceAndAlertsScreen
import com.example.ui.theme.BatteryBossTheme
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.EmeraldCharge
import com.example.ui.theme.NavyCardBorder
import com.example.ui.theme.NavySurface
import com.example.ui.theme.NavySurfaceElevated
import com.example.ui.theme.ObsidianBackground
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.ThermalCrimson
import com.example.viewmodel.BatteryBossViewModel

enum class BatteryBossTab(
    val routeId: String,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
) {
    DASHBOARD("dashboard", "Dashboard", Icons.Filled.Speed, Icons.Outlined.Speed),
    VOICE_ALERTS("voice_alerts", "Voice Studio", Icons.Filled.RecordVoiceOver, Icons.Outlined.RecordVoiceOver),
    BATTERY_HEALTH("battery_health", "Health", Icons.Filled.HealthAndSafety, Icons.Outlined.HealthAndSafety),
    HARDWARE_OEM("hardware_oem", "OEM & Diag", Icons.Filled.DeveloperBoard, Icons.Outlined.DeveloperBoard),
    HISTORY_ABOUT("history_about", "Analytics", Icons.Filled.History, Icons.Outlined.History)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BatteryBossTheme {
                BatteryBossApp()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatteryBossApp(
    viewModel: BatteryBossViewModel = viewModel()
) {
    val context = LocalContext.current
    val snapshot by viewModel.snapshot.collectAsStateWithLifecycle()
    val telemetryHistory by viewModel.telemetryHistory.collectAsStateWithLifecycle()
    val settings by viewModel.userSettings.collectAsStateWithLifecycle()
    val isServiceRunning by viewModel.isServiceRunning.collectAsStateWithLifecycle()
    val isSpeaking by viewModel.isSpeaking.collectAsStateWithLifecycle()
    val previewingPreset by viewModel.previewingPreset.collectAsStateWithLifecycle()
    val engineStatusText by viewModel.engineStatusText.collectAsStateWithLifecycle()
    val voiceModelPacks by viewModel.voiceModelPacks.collectAsStateWithLifecycle()
    val lastSpokenBanner by viewModel.lastSpokenBanner.collectAsStateWithLifecycle()
    val audioDiagnostics by viewModel.audioDiagnostics.collectAsStateWithLifecycle()
    val chargingSessions by viewModel.chargingSessions.collectAsStateWithLifecycle()
    val announcementLogs by viewModel.announcementLogs.collectAsStateWithLifecycle()
    val isIgnoringOptimizations by viewModel.isIgnoringBatteryOptimizations.collectAsStateWithLifecycle()

    var currentTab by rememberSaveable { mutableStateOf(BatteryBossTab.DASHBOARD) }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted && settings.backgroundMonitoringEnabled) {
            viewModel.toggleBackgroundMonitoring(true)
        }
    }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val hasPerm = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasPerm) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    if (currentTab != BatteryBossTab.DASHBOARD) {
        BackHandler {
            currentTab = BatteryBossTab.DASHBOARD
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isExpandedScreen = maxWidth >= 600.dp

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = ObsidianBackground,
            contentWindowInsets = WindowInsets.safeDrawing,
            topBar = {
                TopAppBar(
                    title = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.BatteryChargingFull,
                                contentDescription = "Battery Boss Logo",
                                tint = EmeraldCharge,
                                modifier = Modifier.size(26.dp)
                            )
                            Column {
                                Text(
                                    text = "BATTERY BOSS 2.0",
                                    style = MaterialTheme.typography.titleLarge,
                                    color = TextPrimary,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "${settings.activePreset.title} (${settings.activePreset.personaName}) • ${(settings.voiceVolume * 100).toInt()}% Vol",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = ElectricCyan,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    },
                    actions = {
                        Surface(
                            color = if (snapshot.isCharging) EmeraldCharge.copy(alpha = 0.16f) else NavySurfaceElevated,
                            shape = RoundedCornerShape(50),
                            border = BorderStroke(
                                1.dp,
                                if (snapshot.isCharging) EmeraldCharge else NavyCardBorder
                            )
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = "${snapshot.percentage}%",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = if (snapshot.isCharging) EmeraldCharge else TextPrimary
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        if (isSpeaking) {
                            IconButton(
                                onClick = { viewModel.stopSpeaking() },
                                modifier = Modifier.testTag("topbar_stop_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Stop,
                                    contentDescription = "Stop speaking",
                                    tint = ThermalCrimson
                                )
                            }
                        } else {
                            IconButton(
                                onClick = { viewModel.testVoiceNow() },
                                modifier = Modifier.testTag("topbar_speak_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.VolumeUp,
                                    contentDescription = "Speak battery status",
                                    tint = ElectricCyan
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = NavySurface
                    )
                )
            },
            bottomBar = {
                if (!isExpandedScreen) {
                    NavigationBar(
                        containerColor = NavySurface,
                        tonalElevation = 8.dp
                    ) {
                        BatteryBossTab.entries.forEach { tab ->
                            val selected = currentTab == tab
                            NavigationBarItem(
                                selected = selected,
                                onClick = { currentTab = tab },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                        contentDescription = tab.label
                                    )
                                },
                                label = {
                                    Text(
                                        text = tab.label,
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = Color(0xFF00210E),
                                    selectedTextColor = EmeraldCharge,
                                    indicatorColor = EmeraldCharge,
                                    unselectedIconColor = TextSecondary,
                                    unselectedTextColor = TextSecondary
                                ),
                                modifier = Modifier.testTag("nav_tab_${tab.routeId}")
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (isExpandedScreen) {
                    NavigationRail(
                        containerColor = NavySurface,
                        modifier = Modifier.fillMaxHeight()
                    ) {
                        BatteryBossTab.entries.forEach { tab ->
                            val selected = currentTab == tab
                            NavigationRailItem(
                                selected = selected,
                                onClick = { currentTab = tab },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                        contentDescription = tab.label
                                    )
                                },
                                label = { Text(tab.label) },
                                colors = NavigationRailItemDefaults.colors(
                                    selectedIconColor = Color(0xFF00210E),
                                    selectedTextColor = EmeraldCharge,
                                    indicatorColor = EmeraldCharge,
                                    unselectedIconColor = TextSecondary,
                                    unselectedTextColor = TextSecondary
                                ),
                                modifier = Modifier.testTag("rail_tab_${tab.routeId}")
                            )
                        }
                    }
                }

                val contentModifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()

                when (currentTab) {
                    BatteryBossTab.DASHBOARD -> DashboardScreen(
                        snapshot = snapshot,
                        telemetryHistory = telemetryHistory,
                        settings = settings,
                        isServiceRunning = isServiceRunning,
                        isSpeaking = isSpeaking,
                        previewingPreset = previewingPreset,
                        engineStatusText = engineStatusText,
                        lastSpokenBanner = lastSpokenBanner,
                        onToggleMonitoring = { enable ->
                            if (enable && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                val hasPerm = ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.POST_NOTIFICATIONS
                                ) == PackageManager.PERMISSION_GRANTED
                                if (!hasPerm) {
                                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                }
                            }
                            viewModel.toggleBackgroundMonitoring(enable)
                        },
                        onSpeakStatusNow = { viewModel.testVoiceNow() },
                        onStopSpeaking = { viewModel.stopSpeaking() },
                        onSelectPreset = { viewModel.selectVoicePreset(it) },
                        onPreviewPreset = { viewModel.previewVoicePreset(it) },
                        onOpenHealthScreen = { currentTab = BatteryBossTab.BATTERY_HEALTH },
                        onRefreshTelemetry = { viewModel.refreshHardwareTelemetry() },
                        modifier = contentModifier
                    )

                    BatteryBossTab.VOICE_ALERTS -> VoiceAndAlertsScreen(
                        settings = settings,
                        voiceModelPacks = voiceModelPacks,
                        audioDiagnostics = audioDiagnostics,
                        isSpeaking = isSpeaking,
                        previewingPreset = previewingPreset,
                        onSelectPreset = { viewModel.selectVoicePreset(it) },
                        onPreviewPreset = { preset, phrase -> viewModel.previewVoicePreset(preset, phrase) },
                        onStopSpeaking = { viewModel.stopSpeaking() },
                        onUpdateLanguage = { viewModel.updateLanguage(it) },
                        onUpdateGender = { viewModel.updateGender(it) },
                        onUpdateVoiceParameters = { speed, pitch, vol, prosody ->
                            viewModel.updateVoiceParameters(speed, pitch, vol, prosody)
                        },
                        onLiveVolumeChange = { vol -> viewModel.updateLiveIndependentVolume(vol) },
                        onUpdateIndependentVolumeSettings = { indepEnabled, vol, rememberLast, applyAll, ducking, repeat ->
                            viewModel.updateIndependentVolumeSettings(
                                independentEnabled = indepEnabled,
                                volume = vol,
                                rememberLastVolume = rememberLast,
                                applyToAllAnnouncements = applyAll,
                                mediaDuckingLevel = ducking,
                                repeatPolicy = repeat
                            )
                        },
                        onTestVoice = { customPhrase -> viewModel.testVoiceNow(customPhrase) },
                        onTestPriorityBurst = { viewModel.testPriorityQueueBurst() },
                        onRefreshAudioDiagnostics = { viewModel.refreshAudioDiagnostics() },
                        onInstallOrVerifyModel = { modelId -> viewModel.installOrVerifyModelPack(modelId) },
                        onUpdateChargingAlerts = { intervalPercent, intervalEnabled, smartMilestones, fullCharge, connected, disconnected, lowBat, lowThresh, tempWarn, tempThresh ->
                            viewModel.updateChargingAlerts(
                                intervalPercent = intervalPercent,
                                intervalEnabled = intervalEnabled,
                                smartMilestones = smartMilestones,
                                fullCharge = fullCharge,
                                chargerConnected = connected,
                                chargerDisconnected = disconnected,
                                lowBattery = lowBat,
                                lowBatteryThreshold = lowThresh,
                                tempWarning = tempWarn,
                                tempWarningCelsius = tempThresh
                            )
                        },
                        onUpdateQuietHoursAndRinger = { qEnabled, qStart, qEnd, ringer ->
                            viewModel.updateQuietHoursAndRinger(qEnabled, qStart, qEnd, ringer)
                        },
                        onSaveCustomMessages = { isHindi, conn, disc, m20, m50, m80, inter, full, low, temp ->
                            viewModel.saveCustomMessages(isHindi, conn, disc, m20, m50, m80, inter, full, low, temp)
                        },
                        onResetCustomMessages = { viewModel.resetCustomMessages() },
                        modifier = contentModifier
                    )

                    BatteryBossTab.BATTERY_HEALTH -> BatteryHealthScreen(
                        snapshot = snapshot,
                        modifier = contentModifier
                    )

                    BatteryBossTab.HARDWARE_OEM -> HardwareAndOemScreen(
                        snapshot = snapshot,
                        settings = settings,
                        audioDiagnostics = audioDiagnostics,
                        isServiceRunning = isServiceRunning,
                        isIgnoringBatteryOptimizations = isIgnoringOptimizations,
                        oemGuide = viewModel.oemGuide,
                        onToggleMonitoring = { viewModel.toggleBackgroundMonitoring(it) },
                        onUpdateBackgroundSettings = { boot, notif ->
                            viewModel.updateBackgroundSettings(boot, notif)
                        },
                        onRefreshOptimizationState = { viewModel.refreshHardwareTelemetry() },
                        onRunQuickVoiceTest = { viewModel.testVoiceNow() },
                        onRunQueueBurstTest = { viewModel.testPriorityQueueBurst() },
                        modifier = contentModifier
                    )

                    BatteryBossTab.HISTORY_ABOUT -> HistoryAndAboutScreen(
                        snapshot = snapshot,
                        chargingSessions = chargingSessions,
                        announcementLogs = announcementLogs,
                        onReplayAnnouncement = { msg -> viewModel.testVoiceNow(msg) },
                        onClearHistory = { viewModel.clearAllHistory() },
                        modifier = contentModifier
                    )
                }
            }
        }
    }
}
