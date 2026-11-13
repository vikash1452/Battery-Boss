package com.example.viewmodel

import android.app.Application
import android.content.Context
import android.os.PowerManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.AnnouncementLogEntity
import com.example.data.local.BatteryBossDatabase
import com.example.data.local.BatteryBossRepository
import com.example.data.local.ChargingSessionEntity
import com.example.data.preferences.SettingsDataStore
import com.example.hardware.BatteryHardwareReader
import com.example.model.AnnouncementRepeatPolicy
import com.example.model.AudioDiagnosticsState
import com.example.model.BatteryHardwareSnapshot
import com.example.model.MediaDuckingLevel
import com.example.model.OemOptimizationGuide
import com.example.model.OfflineVoiceModelPack
import com.example.model.TelemetrySamplePoint
import com.example.model.UserSettings
import com.example.model.VoiceGender
import com.example.model.VoiceLanguage
import com.example.model.VoiceProfilePreset
import com.example.service.BatteryMonitorService
import com.example.service.BatteryTelemetryHub
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class BatteryBossViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = application.applicationContext
    private val hardwareReader = BatteryHardwareReader(appContext)
    private val settingsDataStore = SettingsDataStore(appContext)
    private val database = BatteryBossDatabase.getInstance(appContext)
    private val repository = BatteryBossRepository(database.batteryBossDao())
    private val voiceAssistant = BatteryTelemetryHub.getOrCreateVoiceAssistant(appContext)

    val snapshot: StateFlow<BatteryHardwareSnapshot> = BatteryTelemetryHub.snapshot
    val telemetryHistory: StateFlow<List<TelemetrySamplePoint>> = BatteryTelemetryHub.telemetryHistory
    val isServiceRunning: StateFlow<Boolean> = BatteryTelemetryHub.isServiceRunning
    val isSpeaking: StateFlow<Boolean> = voiceAssistant.isSpeaking
    val previewingPreset: StateFlow<VoiceProfilePreset?> = voiceAssistant.previewingPreset
    val engineStatusText: StateFlow<String> = voiceAssistant.engineStatusText
    val voiceModelPacks: StateFlow<List<OfflineVoiceModelPack>> = voiceAssistant.voiceModelPacks
    val lastSpokenBanner: StateFlow<String?> = voiceAssistant.lastSpokenBanner
    val audioDiagnostics: StateFlow<AudioDiagnosticsState> = voiceAssistant.audioDiagnostics

    val userSettings: StateFlow<UserSettings> = settingsDataStore.settingsFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = UserSettings()
    )

    val chargingSessions: StateFlow<List<ChargingSessionEntity>> = repository.chargingSessions.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList()
    )

    val announcementLogs: StateFlow<List<AnnouncementLogEntity>> = repository.announcementLogs.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList()
    )

    val oemGuide: OemOptimizationGuide = hardwareReader.getDetectedOemGuide()

    private val _isIgnoringBatteryOptimizations = MutableStateFlow(checkIgnoringBatteryOptimizations())
    val isIgnoringBatteryOptimizations: StateFlow<Boolean> = _isIgnoringBatteryOptimizations.asStateFlow()

    init {
        refreshHardwareTelemetry()
        viewModelScope.launch {
            val initialSettings = settingsDataStore.getSettingsSnapshot()
            if (initialSettings.backgroundMonitoringEnabled && !BatteryTelemetryHub.isServiceRunning.value) {
                BatteryMonitorService.startService(appContext)
            }
        }
        viewModelScope.launch {
            while (isActive) {
                delay(3_000L)
                if (!BatteryTelemetryHub.isServiceRunning.value) {
                    refreshHardwareTelemetry()
                }
                voiceAssistant.refreshDiagnosticsSnapshot()
            }
        }
    }

    fun refreshHardwareTelemetry() {
        val latest = hardwareReader.readSnapshot()
        BatteryTelemetryHub.updateSnapshot(latest)
        _isIgnoringBatteryOptimizations.value = checkIgnoringBatteryOptimizations()
        voiceAssistant.refreshDiagnosticsSnapshot()
    }

    fun refreshAudioDiagnostics() {
        voiceAssistant.refreshDiagnosticsSnapshot()
    }

    private fun checkIgnoringBatteryOptimizations(): Boolean {
        val pm = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return pm.isIgnoringBatteryOptimizations(appContext.packageName)
    }

    fun toggleBackgroundMonitoring(enable: Boolean) {
        viewModelScope.launch {
            settingsDataStore.updateBackgroundSettings(backgroundMonitoring = enable)
            if (enable) {
                BatteryMonitorService.startService(appContext)
            } else {
                BatteryMonitorService.stopService(appContext)
            }
        }
    }

    fun selectVoicePreset(preset: VoiceProfilePreset) {
        viewModelScope.launch {
            settingsDataStore.selectVoicePreset(preset)
        }
    }

    fun previewVoicePreset(preset: VoiceProfilePreset, customSampleText: String? = null) {
        voiceAssistant.previewVoicePreset(preset, customSampleText)
    }

    fun stopSpeaking() {
        voiceAssistant.stopSpeaking()
    }

    fun updateLanguage(language: VoiceLanguage) {
        viewModelScope.launch {
            settingsDataStore.updateLanguage(language)
        }
    }

    fun updateGender(gender: VoiceGender) {
        viewModelScope.launch {
            settingsDataStore.updateGender(gender)
        }
    }

    fun updateVoiceParameters(speed: Float, pitch: Float, volume: Float, naturalProsody: Boolean? = null) {
        voiceAssistant.updateLiveIndependentVolume(volume)
        viewModelScope.launch {
            settingsDataStore.updateVoiceParameters(speed, pitch, volume, naturalProsody)
        }
    }

    fun updateLiveIndependentVolume(volume: Float) {
        voiceAssistant.updateLiveIndependentVolume(volume)
    }

    fun updateIndependentVolumeSettings(
        independentEnabled: Boolean? = null,
        volume: Float? = null,
        rememberLastVolume: Boolean? = null,
        applyToAllAnnouncements: Boolean? = null,
        mediaDuckingLevel: MediaDuckingLevel? = null,
        repeatPolicy: AnnouncementRepeatPolicy? = null
    ) {
        if (volume != null) {
            voiceAssistant.updateLiveIndependentVolume(volume)
        }
        viewModelScope.launch {
            settingsDataStore.updateIndependentVolumeSettings(
                independentEnabled = independentEnabled,
                volume = volume,
                rememberLastVolume = rememberLastVolume,
                applyToAllAnnouncements = applyToAllAnnouncements,
                mediaDuckingLevel = mediaDuckingLevel,
                repeatPolicy = repeatPolicy
            )
        }
    }

    fun testVoiceNow(customPhrase: String? = null) {
        val currentSnapshot = hardwareReader.readSnapshot()
        BatteryTelemetryHub.updateSnapshot(currentSnapshot)
        voiceAssistant.speakImmediateSample(customMessage = customPhrase, snapshot = currentSnapshot)
    }

    fun testPriorityQueueBurst() {
        val currentSnapshot = hardwareReader.readSnapshot()
        BatteryTelemetryHub.updateSnapshot(currentSnapshot)
        voiceAssistant.enqueuePriorityBurstTest(currentSnapshot)
    }

    fun installOrVerifyModelPack(modelId: String) {
        voiceAssistant.installOrVerifyModelPack(modelId)
    }

    fun saveCustomMessages(
        isHindi: Boolean,
        connected: String,
        disconnected: String,
        milestone20: String,
        milestone50: String,
        milestone80: String,
        interval: String,
        full: String,
        lowBattery: String,
        highTemp: String
    ) {
        viewModelScope.launch {
            settingsDataStore.updateCustomMessages(
                isHindi = isHindi,
                connected = connected,
                disconnected = disconnected,
                milestone20 = milestone20,
                milestone50 = milestone50,
                milestone80 = milestone80,
                interval = interval,
                full = full,
                lowBattery = lowBattery,
                highTemp = highTemp
            )
        }
    }

    fun resetCustomMessages() {
        viewModelScope.launch {
            settingsDataStore.resetCustomMessagesToDefault()
        }
    }

    fun updateChargingAlerts(
        intervalPercent: Int? = null,
        intervalEnabled: Boolean? = null,
        smartMilestones: Boolean? = null,
        fullCharge: Boolean? = null,
        chargerConnected: Boolean? = null,
        chargerDisconnected: Boolean? = null,
        lowBattery: Boolean? = null,
        lowBatteryThreshold: Int? = null,
        tempWarning: Boolean? = null,
        tempWarningCelsius: Int? = null
    ) {
        viewModelScope.launch {
            settingsDataStore.updateChargingAlerts(
                intervalPercent = intervalPercent,
                intervalEnabled = intervalEnabled,
                smartMilestones = smartMilestones,
                fullCharge = fullCharge,
                chargerConnected = chargerConnected,
                chargerDisconnected = chargerDisconnected,
                lowBattery = lowBattery,
                lowBatteryThreshold = lowBatteryThreshold,
                tempWarning = tempWarning,
                tempWarningCelsius = tempWarningCelsius
            )
        }
    }

    fun updateQuietHoursAndRinger(
        quietHoursEnabled: Boolean? = null,
        quietStartHour: Int? = null,
        quietEndHour: Int? = null,
        respectSystemRinger: Boolean? = null
    ) {
        viewModelScope.launch {
            settingsDataStore.updateQuietHoursAndRinger(
                quietHoursEnabled = quietHoursEnabled,
                quietStartHour = quietStartHour,
                quietEndHour = quietEndHour,
                respectSystemRinger = respectSystemRinger
            )
        }
    }

    fun updateBackgroundSettings(
        startOnBoot: Boolean? = null,
        persistentNotification: Boolean? = null
    ) {
        viewModelScope.launch {
            settingsDataStore.updateBackgroundSettings(
                startOnBoot = startOnBoot,
                persistentNotification = persistentNotification
            )
        }
    }

    fun clearAllHistory() {
        viewModelScope.launch {
            repository.clearAllHistory()
        }
    }
}

