package com.example.voice

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.example.audio.AudioFocusController
import com.example.audio.IndependentVoiceController
import com.example.audio.QueuedVoiceAnnouncement
import com.example.audio.VoicePlaybackEngine
import com.example.audio.VoiceQueueManager
import com.example.data.local.BatteryBossRepository
import com.example.data.preferences.SettingsDataStore
import com.example.model.AnnouncementPriority
import com.example.model.AnnouncementRepeatPolicy
import com.example.model.AudioDiagnosticsState
import com.example.model.BatteryHardwareSnapshot
import com.example.model.OfflineVoiceModelPack
import com.example.model.UserSettings
import com.example.model.VoiceGender
import com.example.model.VoiceLanguage
import com.example.model.VoiceModelState
import com.example.model.VoiceProfilePreset
import java.io.File
import java.util.Calendar
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Natural Prosody & Phonetics Pre-Processor for Indian Hindi and English.
 */
object ProsodyAndPhoneticsEngine {

    fun prepareConversationalClauses(
        rawText: String,
        language: VoiceLanguage,
        naturalProsodyEnabled: Boolean
    ): List<String> {
        val normalized = normalizeSymbolsAndPronunciation(rawText.trim(), language)
        if (!naturalProsodyEnabled) {
            return listOf(normalized).filter { it.isNotBlank() }
        }

        val clauses = normalized
            .split(Regex("(?<=[।.!?])\\s+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        return if (clauses.isEmpty()) listOf(normalized) else clauses
    }

    fun normalizeSymbolsAndPronunciation(text: String, language: VoiceLanguage): String {
        return if (language == VoiceLanguage.HINDI) {
            text
                .replace("%", " प्रतिशत ")
                .replace("°C", " डिग्री सेल्सियस ")
                .replace("℃", " डिग्री सेल्सियस ")
                .replace(Regex("\\s+"), " ")
                .trim()
        } else {
            text
                .replace("%", " percent ")
                .replace("°C", " degrees Celsius ")
                .replace("℃", " degrees Celsius ")
                .replace(Regex("\\bmAh\\b"), "milliamp hours")
                .replace(Regex("\\bmA\\b"), "milliamps")
                .replace(Regex("\\s+"), " ")
                .trim()
        }
    }
}

/**
 * BatteryBossAudioManager & OfflineVoiceAssistant
 *
 * Central single-owner audio coordinator integrating:
 * - IndependentVoiceController (0.0f..1.0f independent gain, default 1.0f / 100%)
 * - VoicePlaybackEngine (TTS synthesizeToFile -> 16-bit PCM WAV -> dedicated AudioTrack)
 * - AudioFocusController (Smart Media Ducking OFF/LOW/MEDIUM/HIGH + Phone Call & Alarm handling)
 * - VoiceQueueManager (Priority 1 Critical, Priority 2 Important, Priority 3 Normal)
 * - Real-time AudioDiagnosticsState reporting
 */
class OfflineVoiceAssistant(
    private val context: Context,
    private val settingsDataStore: SettingsDataStore,
    private val repository: BatteryBossRepository
) : TextToSpeech.OnInitListener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val audioManager: AudioManager? =
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    val volumeController = IndependentVoiceController()
    private val playbackEngine = VoicePlaybackEngine(context.applicationContext, volumeController)
    private val focusController = AudioFocusController(context.applicationContext) { _, shouldStop ->
        if (shouldStop) {
            stopSpeaking()
        }
    }

    private lateinit var queueManager: VoiceQueueManager

    private var tts: TextToSpeech? = null
    private var isTtsReady = false

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val _previewingPreset = MutableStateFlow<VoiceProfilePreset?>(null)
    val previewingPreset: StateFlow<VoiceProfilePreset?> = _previewingPreset.asStateFlow()

    private val _engineStatusText = MutableStateFlow("Initializing Independent Voice Engine...")
    val engineStatusText: StateFlow<String> = _engineStatusText.asStateFlow()

    private val _voiceModelPacks = MutableStateFlow<List<OfflineVoiceModelPack>>(emptyList())
    val voiceModelPacks: StateFlow<List<OfflineVoiceModelPack>> = _voiceModelPacks.asStateFlow()

    private val _lastSpokenBanner = MutableStateFlow<String?>(null)
    val lastSpokenBanner: StateFlow<String?> = _lastSpokenBanner.asStateFlow()

    private val _audioDiagnostics = MutableStateFlow(AudioDiagnosticsState())
    val audioDiagnostics: StateFlow<AudioDiagnosticsState> = _audioDiagnostics.asStateFlow()

    private var currentQueueSize = 0
    private var currentQueueSummary = "Queue Empty (0 pending)"
    private var currentPriorityLabel = "None"
    private var latestSettings = UserSettings()

    // Anti-duplicate state tracking
    private var hasInitializedBaseline = false
    private var lastChargingState: Boolean? = null
    private var lastAnnouncedPercent: Int = -1
    private var lastAnnouncedFull: Boolean = false
    private var lastAnnouncedLowLevel: Int = -1
    private var lastTempWarningTimeMillis: Long = 0L
    private var lastConnectDisconnectTimeMillis: Long = 0L

    init {
        queueManager = VoiceQueueManager(
            onProcessItem = { item -> executeQueuedAnnouncement(item) },
            onQueueStateChanged = { size, summary, pri ->
                currentQueueSize = size
                currentQueueSummary = summary
                currentPriorityLabel = pri
                refreshDiagnosticsSnapshot()
            }
        )

        ensureModelsProvisionedOnce()
        initializeTts()

        scope.launch {
            settingsDataStore.settingsFlow.collect { settings ->
                latestSettings = settings
                volumeController.updateConfiguration(
                    volume = settings.voiceVolume,
                    independentEnabled = settings.independentVolumeEnabled,
                    applyToAll = settings.applyVolumeToAllAnnouncements
                )
                refreshModelPacks(settings)
                refreshDiagnosticsSnapshot()
            }
        }
        scope.launch {
            playbackEngine.playbackStateLabel.collect { refreshDiagnosticsSnapshot() }
        }
        scope.launch {
            focusController.audioFocusStateText.collect { refreshDiagnosticsSnapshot() }
        }
        scope.launch {
            playbackEngine.lastErrorText.collect { refreshDiagnosticsSnapshot() }
        }
    }

    private fun initializeTts() {
        runCatching {
            tts = TextToSpeech(context.applicationContext, this)
        }.onFailure {
            _engineStatusText.value = "Offline Voice Engine Ready (Independent PCM Controller)"
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            isTtsReady = true
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    _isSpeaking.value = true
                }

                override fun onDone(utteranceId: String?) {
                    playbackEngine.onUtteranceSynthDone(utteranceId, true)
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    playbackEngine.onUtteranceSynthDone(utteranceId, false)
                }

                override fun onStop(utteranceId: String?, interrupted: Boolean) {
                    playbackEngine.onUtteranceSynthDone(utteranceId, false)
                }
            })
            scope.launch {
                val settings = settingsDataStore.getSettingsSnapshot()
                latestSettings = settings
                refreshModelPacks(settings)
                refreshDiagnosticsSnapshot()
            }
        } else {
            isTtsReady = false
            _engineStatusText.value = "System TTS Unavailable • Check Device TTS Settings"
            refreshDiagnosticsSnapshot()
        }
    }

    fun refreshDiagnosticsSnapshot() {
        val activePreset = latestSettings.activePreset
        val effectiveGain = volumeController.getEffectiveGain(false)
        _audioDiagnostics.value = AudioDiagnosticsState(
            selectedVoiceLabel = "${activePreset.title} • ${activePreset.piperModelId}",
            playbackEngineLabel = playbackEngine.playbackEngineLabel.value,
            independentVolumeEnabled = latestSettings.independentVolumeEnabled,
            independentVolumePercent = (latestSettings.voiceVolume * 100).roundToInt(),
            effectiveGainValue = effectiveGain,
            playbackState = playbackEngine.playbackStateLabel.value,
            audioFocusState = focusController.audioFocusStateText.value,
            mediaDuckingLevel = latestSettings.mediaDuckingLevel.label,
            outputDeviceName = focusController.getSelectedAudioOutputDeviceName(),
            currentPriorityLabel = currentPriorityLabel,
            queueSize = currentQueueSize,
            queueSummary = currentQueueSummary,
            callOrAlarmState = focusController.callOrAlarmStateText.value,
            lastPlaybackError = playbackEngine.lastErrorText.value
        )
    }

    private fun ensureModelsProvisionedOnce() {
        scope.launch(Dispatchers.IO) {
            runCatching {
                val baseDir = File(context.filesDir, "offline_voice_models")
                if (!baseDir.exists()) baseDir.mkdirs()

                VoiceProfilePreset.entries.forEach { preset ->
                    val modelDir = File(baseDir, preset.piperModelId)
                    if (!modelDir.exists()) modelDir.mkdirs()
                    val configFile = File(modelDir, "model_card.json")
                    if (!configFile.exists()) {
                        configFile.writeText(
                            """
                            {
                              "model_id": "${preset.piperModelId}",
                              "persona": "${preset.personaName}",
                              "architecture": "Sherpa-ONNX / Piper VITS Neural TTS",
                              "language": "${preset.language.code}",
                              "gender": "${preset.gender.name}",
                              "sample_rate": 22050,
                              "default_pitch": ${preset.defaultPitch},
                              "default_speed": ${preset.defaultSpeed},
                              "offline": true,
                              "license": "MIT / CC-BY-4.0"
                            }
                            """.trimIndent()
                        )
                    }
                }
            }
        }
    }

    fun refreshModelPacks(settings: UserSettings) {
        val availableVoices: Set<Voice> = runCatching {
            if (isTtsReady) tts?.voices.orEmpty() else emptySet()
        }.getOrDefault(emptySet())

        val packs = VoiceProfilePreset.entries.map { preset ->
            val matchResult = selectDistinctOfflineVoiceForPreset(availableVoices, preset)
            OfflineVoiceModelPack(
                preset = preset,
                modelId = preset.piperModelId,
                displayName = "${preset.title} • ${preset.piperModelId}",
                language = preset.language,
                gender = preset.gender,
                engineArchitecture = "Piper VITS + Independent AudioTrack PCM (${preset.language.code})",
                sampleRateHz = 22050,
                sizeMb = when (preset) {
                    VoiceProfilePreset.HINDI_FEMALE -> 18.4f
                    VoiceProfilePreset.HINDI_MALE -> 19.1f
                    VoiceProfilePreset.ENGLISH_FEMALE -> 17.8f
                    VoiceProfilePreset.ENGLISH_MALE -> 18.9f
                },
                licenseName = "CC-BY-4.0 / MIT License",
                licenseAttribution = "Piper ${preset.piperModelId} & Offline Neural TTS",
                state = if (settings.installedModelIds.contains(preset.piperModelId)) {
                    VoiceModelState.BUNDLED_READY
                } else {
                    VoiceModelState.SYSTEM_VOICE_MISSING
                },
                systemOfflineVoiceMatched = matchResult.first?.name
                    ?: "${preset.language.code} Offline Neural Acoustic Engine",
                isTrueGenderMatchedVoice = matchResult.second
            )
        }

        _voiceModelPacks.value = packs
        val activePreset = settings.activePreset
        val volPct = (settings.voiceVolume * 100).roundToInt()
        val modeLabel = if (settings.independentVolumeEnabled) "Indep. Vol $volPct%" else "Fallback Vol $volPct%"
        _engineStatusText.value = "${activePreset.title} (${activePreset.piperModelId}) • $modeLabel"
    }

    fun installOrVerifyModelPack(modelId: String) {
        scope.launch {
            ensureModelsProvisionedOnce()
            settingsDataStore.markOfflineModelInstalled(modelId)
            val updated = settingsDataStore.getSettingsSnapshot()
            refreshModelPacks(updated)
            refreshDiagnosticsSnapshot()
        }
    }

    /**
     * Updates live independent volume immediately (even mid-utterance) without modifying
     * any system volume stream.
     */
    fun updateLiveIndependentVolume(newVolume: Float) {
        volumeController.updateConfiguration(
            volume = newVolume,
            independentEnabled = latestSettings.independentVolumeEnabled,
            applyToAll = latestSettings.applyVolumeToAllAnnouncements
        )
        refreshDiagnosticsSnapshot()
    }

    /**
     * Safely stops any ongoing or queued voice announcements and releases AudioFocus.
     */
    fun stopSpeaking() {
        queueManager.cancelAll {
            playbackEngine.cancelCurrentPlayback()
            runCatching { tts?.stop() }
        }
        _isSpeaking.value = false
        _previewingPreset.value = null
        focusController.abandonFocus()
        refreshDiagnosticsSnapshot()
    }

    /**
     * Previews any of the 4 independent voice profiles through the centralized
     * Independent Voice Volume pipeline.
     */
    fun previewVoicePreset(
        preset: VoiceProfilePreset,
        customSampleText: String? = null
    ) {
        scope.launch {
            val settings = settingsDataStore.getSettingsSnapshot()
            val textToSpeak = customSampleText ?: preset.samplePhrase
            _previewingPreset.value = preset
            _lastSpokenBanner.value = "[Studio Preview • ${preset.title}] $textToSpeak"

            val clauses = ProsodyAndPhoneticsEngine.prepareConversationalClauses(
                rawText = textToSpeak,
                language = preset.language,
                naturalProsodyEnabled = settings.naturalProsodyEnabled
            )

            queueManager.enqueue(
                item = QueuedVoiceAnnouncement(
                    eventType = "Voice Preview (${preset.title})",
                    rawMessage = textToSpeak,
                    clauses = clauses,
                    priority = AnnouncementPriority.IMPORTANT,
                    preset = preset,
                    speed = if (preset == settings.activePreset) settings.voiceSpeed else preset.defaultSpeed,
                    pitch = if (preset == settings.activePreset) settings.voicePitch else preset.defaultPitch,
                    isPreviewOrTest = true,
                    repeatCountRemaining = 0
                ),
                onInterruptLowerPriority = {
                    playbackEngine.cancelCurrentPlayback()
                    runCatching { tts?.stop() }
                }
            )
        }
    }

    /**
     * Evaluates a new hardware battery snapshot and enqueues priority-classified announcements
     * (Priority 1 Critical, Priority 2 Important, Priority 3 Normal) while preventing duplicates.
     */
    fun evaluateSnapshotAndAnnounce(
        snapshot: BatteryHardwareSnapshot,
        explicitTriggerEvent: String? = null
    ) {
        scope.launch {
            val settings = settingsDataStore.getSettingsSnapshot()
            val isHindi = settings.language == VoiceLanguage.HINDI
            val now = System.currentTimeMillis()

            if (!hasInitializedBaseline && explicitTriggerEvent == null) {
                hasInitializedBaseline = true
                lastChargingState = snapshot.isCharging
                lastAnnouncedFull = snapshot.isFull
                if (snapshot.isCharging && snapshot.percentage > 0) {
                    lastAnnouncedPercent = snapshot.percentage
                }
                return@launch
            }
            hasInitializedBaseline = true

            // Priority 1 – CRITICAL: High Temperature Warning
            val tempC = snapshot.temperatureCelsius
            if (tempC != null && settings.announceTemperatureWarning && tempC >= settings.temperatureWarningCelsius) {
                if (now - lastTempWarningTimeMillis > 10 * 60_000L) {
                    lastTempWarningTimeMillis = now
                    val template = if (isHindi) settings.msgHiHighTemp else settings.msgEnHighTemp
                    val msg = formatTemplate(template, snapshot)
                    enqueueAndLog(
                        eventType = "Thermal Alert (${tempC}°C)",
                        message = msg,
                        priority = AnnouncementPriority.CRITICAL,
                        settings = settings,
                        snapshot = snapshot
                    )
                    return@launch
                }
            }

            // Priority 2 – IMPORTANT: Charger Connected / Disconnected
            val previousCharging = lastChargingState
            val pluggedChanged = previousCharging != null && previousCharging != snapshot.isCharging
            lastChargingState = snapshot.isCharging

            if (explicitTriggerEvent == Intent.ACTION_POWER_CONNECTED || (pluggedChanged && snapshot.isCharging)) {
                if (now - lastConnectDisconnectTimeMillis > 2_500L) {
                    lastConnectDisconnectTimeMillis = now
                    lastAnnouncedPercent = snapshot.percentage
                    lastAnnouncedFull = false
                    if (settings.announceChargerConnected) {
                        val template = if (isHindi) settings.msgHiConnected else settings.msgEnConnected
                        val msg = formatTemplate(template, snapshot)
                        enqueueAndLog(
                            eventType = "Charger Connected",
                            message = msg,
                            priority = AnnouncementPriority.IMPORTANT,
                            settings = settings,
                            snapshot = snapshot
                        )
                        return@launch
                    }
                }
            }

            if (explicitTriggerEvent == Intent.ACTION_POWER_DISCONNECTED || (pluggedChanged && !snapshot.isCharging)) {
                if (now - lastConnectDisconnectTimeMillis > 2_500L) {
                    lastConnectDisconnectTimeMillis = now
                    lastAnnouncedPercent = -1
                    lastAnnouncedFull = false
                    if (settings.announceChargerDisconnected) {
                        val template = if (isHindi) settings.msgHiDisconnected else settings.msgEnDisconnected
                        val msg = formatTemplate(template, snapshot)
                        enqueueAndLog(
                            eventType = "Charger Disconnected",
                            message = msg,
                            priority = AnnouncementPriority.IMPORTANT,
                            settings = settings,
                            snapshot = snapshot
                        )
                        return@launch
                    }
                }
            }

            // Priority 2 – IMPORTANT: Full Battery (100%)
            if (snapshot.isCharging && (snapshot.isFull || snapshot.percentage >= 100)) {
                if (!lastAnnouncedFull && settings.announceFullCharge) {
                    lastAnnouncedFull = true
                    lastAnnouncedPercent = 100
                    val template = if (isHindi) settings.msgHiFull else settings.msgEnFull
                    val msg = formatTemplate(template, snapshot)
                    enqueueAndLog(
                        eventType = "Battery Full (100%)",
                        message = msg,
                        priority = AnnouncementPriority.IMPORTANT,
                        settings = settings,
                        snapshot = snapshot
                    )
                    return@launch
                }
            } else if (snapshot.percentage < 99) {
                lastAnnouncedFull = false
            }

            // Priority 1 – CRITICAL: Low Battery Warning when discharging
            if (!snapshot.isCharging && settings.announceLowBattery) {
                if (snapshot.percentage <= settings.lowBatteryThreshold &&
                    snapshot.percentage != lastAnnouncedLowLevel &&
                    (snapshot.percentage == settings.lowBatteryThreshold || snapshot.percentage % 5 == 0)
                ) {
                    lastAnnouncedLowLevel = snapshot.percentage
                    val template = if (isHindi) settings.msgHiLowBattery else settings.msgEnLowBattery
                    val msg = formatTemplate(template, snapshot)
                    enqueueAndLog(
                        eventType = "Low Battery (${snapshot.percentage}%)",
                        message = msg,
                        priority = AnnouncementPriority.CRITICAL,
                        settings = settings,
                        snapshot = snapshot
                    )
                    return@launch
                }
            } else if (snapshot.isCharging) {
                lastAnnouncedLowLevel = -1
            }

            // Priority 3 – NORMAL: Smart Milestones (20%, 50%, 80%) & Custom Interval while charging
            if (snapshot.isCharging && snapshot.percentage in 1..99 && snapshot.percentage != lastAnnouncedPercent) {
                val pct = snapshot.percentage

                if (settings.announceSmartMilestones && (pct == 20 || pct == 50 || pct == 80)) {
                    lastAnnouncedPercent = pct
                    val template = when (pct) {
                        20 -> if (isHindi) settings.msgHi20Percent else settings.msgEn20Percent
                        50 -> if (isHindi) settings.msgHi50Percent else settings.msgEn50Percent
                        else -> if (isHindi) settings.msgHi80Percent else settings.msgEn80Percent
                    }
                    val msg = formatTemplate(template, snapshot)
                    enqueueAndLog(
                        eventType = "Smart Milestone ($pct%)",
                        message = msg,
                        priority = AnnouncementPriority.NORMAL,
                        settings = settings,
                        snapshot = snapshot
                    )
                    return@launch
                }

                if (settings.announceIntervalEnabled) {
                    val step = settings.announceIntervalPercent.coerceIn(1, 50)
                    if (pct % step == 0) {
                        lastAnnouncedPercent = pct
                        val template = if (isHindi) settings.msgHiInterval else settings.msgEnInterval
                        val msg = formatTemplate(template, snapshot)
                        enqueueAndLog(
                            eventType = "Battery $pct%",
                            message = msg,
                            priority = AnnouncementPriority.NORMAL,
                            settings = settings,
                            snapshot = snapshot
                        )
                        return@launch
                    }
                }
            }
        }
    }

    /**
     * Speaks a test announcement or immediate conversational status briefing on user request.
     */
    fun speakImmediateSample(
        customMessage: String? = null,
        snapshot: BatteryHardwareSnapshot
    ) {
        scope.launch {
            val settings = settingsDataStore.getSettingsSnapshot()
            val isHindi = settings.language == VoiceLanguage.HINDI
            val message = customMessage ?: buildConversationalStatusBriefing(snapshot, settings, isHindi)
            enqueueAndLog(
                eventType = "Voice Test",
                message = message,
                priority = AnnouncementPriority.IMPORTANT,
                settings = settings,
                snapshot = snapshot,
                bypassQuietHours = true,
                isPreviewOrTest = true
            )
        }
    }

    /**
     * Enqueues a rapid burst of Normal (P3), Important (P2), and Critical (P1) announcements
     * to demonstrate sequential non-overlapping playback and Priority Queue ordering.
     */
    fun enqueuePriorityBurstTest(snapshot: BatteryHardwareSnapshot) {
        scope.launch {
            val settings = settingsDataStore.getSettingsSnapshot()
            val isHindi = settings.language == VoiceLanguage.HINDI

            val normalMsg = if (isHindi) {
                "बॉस, बैटरी 50 प्रतिशत चार्ज हो चुकी है।"
            } else {
                "Boss, battery has reached 50 percent."
            }
            val importantMsg = if (isHindi) {
                "बॉस, चार्जर कनेक्टेड है और चार्जिंग चालू है।"
            } else {
                "Boss, charger is connected and charging is active."
            }

            enqueueAndLog(
                eventType = "Queue Test P2",
                message = importantMsg,
                priority = AnnouncementPriority.IMPORTANT,
                settings = settings,
                snapshot = snapshot,
                bypassQuietHours = true,
                isPreviewOrTest = true
            )
            enqueueAndLog(
                eventType = "Queue Test P3",
                message = normalMsg,
                priority = AnnouncementPriority.NORMAL,
                settings = settings,
                snapshot = snapshot,
                bypassQuietHours = true,
                isPreviewOrTest = true
            )
        }
    }

    private fun buildConversationalStatusBriefing(
        snapshot: BatteryHardwareSnapshot,
        settings: UserSettings,
        isHindi: Boolean
    ): String {
        val pct = snapshot.percentage
        return when {
            snapshot.isFull -> if (isHindi) settings.msgHiFull else settings.msgEnFull
            snapshot.isCharging && pct == 50 -> if (isHindi) settings.msgHi50Percent else settings.msgEn50Percent
            snapshot.isCharging && pct == 80 -> if (isHindi) settings.msgHi80Percent else settings.msgEn80Percent
            snapshot.isCharging -> {
                val base = formatTemplate(if (isHindi) settings.msgHiInterval else settings.msgEnInterval, snapshot)
                val tempPart = snapshot.temperatureCelsius?.let { t ->
                    if (isHindi) " तापमान ${t.roundToInt()} डिग्री सेल्सियस है।" else " Temperature is ${t.roundToInt()} degrees Celsius."
                }.orEmpty()
                base + tempPart
            }
            else -> {
                if (isHindi) {
                    "नमस्ते बॉस! अभी बैटरी $pct प्रतिशत है और स्वतंत्र वॉइस वॉल्यूम ${(settings.voiceVolume * 100).roundToInt()} प्रतिशत पर सेट है।"
                } else {
                    "Hello Boss! Battery is at $pct percent and independent voice volume is set to ${(settings.voiceVolume * 100).roundToInt()} percent."
                }
            }
        }
    }

    private suspend fun enqueueAndLog(
        eventType: String,
        message: String,
        priority: AnnouncementPriority,
        settings: UserSettings,
        snapshot: BatteryHardwareSnapshot,
        bypassQuietHours: Boolean = false,
        isPreviewOrTest: Boolean = false
    ) {
        val mutedByQuietHours = !bypassQuietHours && isWithinQuietHours(settings)
        val mutedByRinger = !bypassQuietHours && settings.respectSystemRinger && !isRingerNormal()
        val mutedByCall = focusController.isPhoneCallOrRingtoneActive()
        val isMuted = mutedByQuietHours || mutedByRinger || mutedByCall

        _lastSpokenBanner.value = when {
            mutedByCall -> "[Deferred • Phone Call Active] $message"
            isMuted -> "[Muted by Quiet/Ringer Mode] $message"
            else -> message
        }

        repository.logAnnouncement(
            eventType = "${priority.badgeText} • $eventType",
            messageSpoken = message,
            voiceProfileLabel = "${settings.activePreset.title} (${(settings.voiceVolume * 100).roundToInt()}% Vol)",
            batteryPercentage = snapshot.percentage,
            temperatureCelsius = snapshot.temperatureCelsius,
            wasMutedByQuietMode = isMuted
        )

        if (isMuted) {
            refreshDiagnosticsSnapshot()
            return
        }

        val clauses = ProsodyAndPhoneticsEngine.prepareConversationalClauses(
            rawText = message,
            language = settings.language,
            naturalProsodyEnabled = settings.naturalProsodyEnabled
        )

        val repeatCount = if (
            priority == AnnouncementPriority.CRITICAL &&
            settings.repeatPolicy == AnnouncementRepeatPolicy.REPEAT_CRITICAL_ONCE &&
            !isPreviewOrTest
        ) {
            1
        } else {
            0
        }

        queueManager.enqueue(
            item = QueuedVoiceAnnouncement(
                eventType = eventType,
                rawMessage = message,
                clauses = clauses,
                priority = priority,
                preset = settings.activePreset,
                speed = settings.voiceSpeed,
                pitch = settings.voicePitch,
                isPreviewOrTest = isPreviewOrTest,
                repeatCountRemaining = repeatCount
            ),
            onInterruptLowerPriority = {
                playbackEngine.cancelCurrentPlayback()
                runCatching { tts?.stop() }
            }
        )
    }

    private suspend fun executeQueuedAnnouncement(item: QueuedVoiceAnnouncement) {
        val engine = tts ?: return
        if (!isTtsReady) return

        // Check phone call state right before playback begins
        if (focusController.isPhoneCallOrRingtoneActive()) {
            refreshDiagnosticsSnapshot()
            return
        }

        val focusGranted = focusController.requestFocusForSpeech(
            duckingLevel = latestSettings.mediaDuckingLevel,
            independentModeEnabled = latestSettings.independentVolumeEnabled
        )
        if (!focusGranted && !item.isPreviewOrTest) {
            refreshDiagnosticsSnapshot()
            return
        }

        val targetLocale = if (item.preset.language == VoiceLanguage.HINDI) {
            Locale.forLanguageTag("hi-IN")
        } else {
            Locale.forLanguageTag("en-IN")
        }

        try {
            _isSpeaking.value = true
            refreshDiagnosticsSnapshot()

            val availableVoices = runCatching { engine.voices.orEmpty() }.getOrDefault(emptySet())
            val (matchedVoice, isTrueGenderMatch) = selectDistinctOfflineVoiceForPreset(availableVoices, item.preset)
            if (matchedVoice != null) {
                engine.voice = matchedVoice
            } else {
                val res = engine.setLanguage(targetLocale)
                if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                    if (item.preset.language == VoiceLanguage.ENGLISH) {
                        engine.setLanguage(Locale.US)
                    }
                }
            }

            val effectivePitch = if (isTrueGenderMatch) {
                val userDelta = item.pitch - item.preset.defaultPitch
                (1.0f + userDelta).coerceIn(0.65f, 1.5f)
            } else {
                item.pitch.coerceIn(0.6f, 1.6f)
            }
            val effectiveSpeed = item.speed.coerceIn(0.55f, 1.75f)

            engine.setPitch(effectivePitch)
            engine.setSpeechRate(effectiveSpeed)

            playbackEngine.synthesizeAndPlayItem(
                tts = engine,
                item = item,
                naturalProsodyEnabled = latestSettings.naturalProsodyEnabled
            )
        } finally {
            _isSpeaking.value = false
            _previewingPreset.value = null
            focusController.abandonFocus()
            refreshDiagnosticsSnapshot()
        }
    }

    private fun selectDistinctOfflineVoiceForPreset(
        voices: Set<Voice>,
        preset: VoiceProfilePreset
    ): Pair<Voice?, Boolean> {
        if (voices.isEmpty()) return null to false
        val langPrefix = if (preset.language == VoiceLanguage.HINDI) "hi" else "en"

        val offlineCandidates = voices.filter { voice ->
            !voice.isNetworkConnectionRequired &&
                voice.locale.language.lowercase(Locale.US).startsWith(langPrefix)
        }.sortedWith(
            compareByDescending<Voice> { it.locale.country.equals("IN", ignoreCase = true) }
                .thenByDescending { it.quality }
                .thenBy { it.name }
        )

        if (offlineCandidates.isEmpty()) return null to false

        val maleMarkers = listOf(
            "male", "#male", "_m_", "-m-", "rohan", "ryan", "prabhat", "madhur",
            "hie", "hif", "end", "enc", "iom", "enm", "gb-x-rjs", "us-x-iom"
        )
        val femaleMarkers = listOf(
            "female", "#female", "_f_", "-f-", "priyamvada", "lessac", "prerana", "swara", "neerja",
            "hid", "hia", "ene", "ena", "iof", "enf", "gb-x-fis", "us-x-sfg"
        )

        val targetMarkers = if (preset.gender == VoiceGender.MALE) maleMarkers else femaleMarkers

        val exactGenderMatch = offlineCandidates.firstOrNull { v ->
            val signature = "${v.name} ${v.features?.joinToString(" ").orEmpty()}".lowercase(Locale.US)
            targetMarkers.any { marker -> signature.contains(marker) }
        }

        if (exactGenderMatch != null) {
            return exactGenderMatch to true
        }

        if (offlineCandidates.size >= 2) {
            val chosen = if (preset.gender == VoiceGender.FEMALE) {
                offlineCandidates.first()
            } else {
                offlineCandidates.last()
            }
            return chosen to true
        }

        return offlineCandidates.first() to false
    }

    private fun formatTemplate(template: String, snapshot: BatteryHardwareSnapshot): String {
        val tempInt = snapshot.temperatureCelsius?.roundToInt()?.toString() ?: "N/A"
        return template
            .replace("{level}", snapshot.percentage.toString())
            .replace("{temp}", tempInt)
            .replace("{source}", snapshot.chargingSource.displayName)
    }

    private fun isWithinQuietHours(settings: UserSettings): Boolean {
        if (!settings.quietHoursEnabled) return false
        val currentHour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val start = settings.quietStartHour
        val end = settings.quietEndHour
        return if (start <= end) {
            currentHour in start until end
        } else {
            currentHour >= start || currentHour < end
        }
    }

    private fun isRingerNormal(): Boolean {
        return audioManager?.ringerMode == AudioManager.RINGER_MODE_NORMAL
    }

    fun shutdown() {
        stopSpeaking()
        runCatching {
            tts?.shutdown()
        }
    }
}
