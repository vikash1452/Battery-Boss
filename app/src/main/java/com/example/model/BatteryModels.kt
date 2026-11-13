package com.example.model

/**
 * Charging source reported by Android BatteryManager.EXTRA_PLUGGED.
 */
enum class ChargingSource(val displayName: String) {
    AC("AC Wall Charger"),
    USB("USB Port"),
    WIRELESS("Wireless Pad"),
    DOCK("Dock Charger"),
    NONE("On Battery")
}

/**
 * Classification of measured battery-side charging power.
 */
enum class ChargingSpeedTier(val label: String, val description: String) {
    NOT_CHARGING("Discharging", "Device is running on battery power"),
    SLOW("Slow Charging (< 5W)", "Standard USB or trickle charge rate"),
    NORMAL("Normal Charging (5W–15W)", "Standard AC adapter charging rate"),
    FAST("Fast Charging (15W–30W)", "High-speed PD / QC / VOOC fast charging"),
    SUPER_FAST("Super Fast (30W+)", "Ultra-fast high-current cell charging"),
    UNAVAILABLE("Speed N/A", "Kernel does not expose live current (mA) sensor")
}

/**
 * Confidence level and hardware origin for each battery diagnostic metric.
 */
enum class MetricConfidence(val badgeText: String) {
    HIGH_DIRECT("High • Direct Hardware Register"),
    MEDIUM_DERIVED("Medium • Derived from Hardware Counters"),
    QUALITATIVE_OS("OS Status Flag (Non-Numeric)"),
    UNAVAILABLE("N/A • Not Exposed by Device Kernel")
}

/**
 * Represents a single transparent hardware diagnostic metric with its API source,
 * confidence level, and explanation if unavailable.
 */
data class DiagnosticMetric(
    val title: String,
    val value: String,
    val unit: String = "",
    val sourceApi: String,
    val confidence: MetricConfidence,
    val explanation: String
)

/**
 * Point-in-time telemetry sample for the real-time Dashboard Charging History Graph.
 */
data class TelemetrySamplePoint(
    val timestampMillis: Long,
    val percentage: Int,
    val powerWatts: Float?,
    val currentMilliAmps: Int?,
    val temperatureCelsius: Float?
)

/**
 * Deep Battery Health Analysis report with explicit hardware source and confidence metadata.
 * Never claims Android's GOOD status means 100% battery health.
 */
data class BatteryHealthReport(
    val reportedOsHealthStatus: String,
    val osHealthExplanation: String,
    val cycleCount: Int?,
    val cycleCountSource: String,
    val designCapacityMah: Int?,
    val designCapacitySource: String,
    val fullChargeCapacityMah: Int?,
    val fullChargeCapacitySource: String,
    val remainingCapacityMah: Int?,
    val remainingCapacitySource: String,
    val energyCounterWh: Float?,
    val measuredCapacityHealthPercent: Float?,
    val degradationSummary: String,
    val temperatureStatus: String
)

/**
 * Real hardware snapshot collected from Android BatteryManager, ACTION_BATTERY_CHANGED,
 * and readable kernel power_supply nodes.
 */
data class BatteryHardwareSnapshot(
    val percentage: Int = 0,
    val isCharging: Boolean = false,
    val isFull: Boolean = false,
    val statusLabel: String = "Unknown",
    val chargingSource: ChargingSource = ChargingSource.NONE,
    val voltageVolts: Float? = null,
    val voltageMillivolts: Int? = null,
    val currentMilliAmps: Int? = null,
    val averageCurrentMilliAmps: Int? = null,
    val powerWatts: Float? = null,
    val averagePowerWatts: Float? = null,
    val temperatureCelsius: Float? = null,
    val healthLabel: String = "N/A",
    val technology: String? = null,
    val chargeCounterMah: Int? = null,
    val fullChargeCapacityMah: Int? = null,
    val designCapacityMah: Int? = null,
    val cycleCount: Int? = null,
    val energyCounterWh: Float? = null,
    val estimatedMinutesToFull: Int? = null,
    val chargingSpeedTier: ChargingSpeedTier = ChargingSpeedTier.NOT_CHARGING,
    val percentPerHourRate: Float? = null,
    val isBatteryPresent: Boolean = true,
    val healthReport: BatteryHealthReport = BatteryHealthReport(
        reportedOsHealthStatus = "N/A",
        osHealthExplanation = "Awaiting hardware broadcast",
        cycleCount = null,
        cycleCountSource = "Not reported by kernel",
        designCapacityMah = null,
        designCapacitySource = "Not reported by kernel",
        fullChargeCapacityMah = null,
        fullChargeCapacitySource = "Not reported by kernel",
        remainingCapacityMah = null,
        remainingCapacitySource = "Not reported by kernel",
        energyCounterWh = null,
        measuredCapacityHealthPercent = null,
        degradationSummary = "N/A — Hardware does not expose full-charge coulomb counter vs design capacity.",
        temperatureStatus = "N/A"
    ),
    val diagnosticMetrics: List<DiagnosticMetric> = emptyList(),
    val timestampMillis: Long = System.currentTimeMillis()
)

enum class VoiceLanguage(val code: String, val displayName: String, val nativeName: String) {
    HINDI("hi-IN", "Hindi", "हिन्दी (Indian Hindi)"),
    ENGLISH("en-IN", "English", "English (Indian English)")
}

enum class VoiceGender(val displayName: String, val hindiLabel: String) {
    FEMALE("Female Voice", "महिला आवाज़"),
    MALE("Male Voice", "पुरुष आवाज़")
}

/**
 * Voice Volume Presets for the Independent Voice Volume System.
 */
enum class VoiceVolumePreset(val label: String, val volumeFraction: Float, val percentLabel: String) {
    VERY_LOW("Very Low", 0.20f, "20%"),
    LOW("Low", 0.40f, "40%"),
    MEDIUM("Medium", 0.60f, "60%"),
    HIGH("High", 0.80f, "80%"),
    MAXIMUM("Maximum", 1.00f, "100%");

    companion object {
        fun matchingPresetOrNull(volume: Float): VoiceVolumePreset? {
            return entries.firstOrNull { kotlin.math.abs(it.volumeFraction - volume) < 0.03f }
        }
    }
}

/**
 * Smart Audio Focus & Media Ducking level.
 */
enum class MediaDuckingLevel(val label: String, val description: String) {
    OFF("OFF", "Do not request media ducking; speaks alongside other apps"),
    LOW("LOW", "Request light transient audio ducking (AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)"),
    MEDIUM("MEDIUM", "Standard speech navigation ducking for music & video players"),
    HIGH("HIGH", "Exclusive transient focus (pauses or strongly ducks background media)")
}

/**
 * Announcement priority levels for the centralized VoiceQueueManager.
 * Lower rank number = Higher urgency.
 */
enum class AnnouncementPriority(val rank: Int, val label: String, val badgeText: String) {
    CRITICAL(1, "Priority 1 – Critical", "P1 CRITICAL"),
    IMPORTANT(2, "Priority 2 – Important", "P2 IMPORTANT"),
    NORMAL(3, "Priority 3 – Normal", "P3 NORMAL")
}

/**
 * Configurable repeat policy for queued announcements.
 */
enum class AnnouncementRepeatPolicy(val label: String, val description: String) {
    NONE("Play Once", "Speak each announcement a single time"),
    REPEAT_CRITICAL_ONCE("Repeat Critical Alerts", "Repeat Priority 1 (Overheat / Critical Low) twice for safety")
}

/**
 * Detailed trace record captured for every announcement playback attempt.
 */
data class PlaybackTraceRecord(
    val timestampMillis: Long = System.currentTimeMillis(),
    val entryPoint: String = "None",
    val selectedTtsEngine: String = "N/A",
    val synthesizeToFileSucceeded: Boolean = false,
    val pcmWavDecoded: Boolean = false,
    val decodedWavFormatSummary: String = "N/A",
    val audioTrackCreated: Boolean = false,
    val actualAudioAttributesUsage: String = "USAGE_ASSISTANCE_ACCESSIBILITY (11)",
    val actualAudioAttributesContentType: String = "CONTENT_TYPE_SPEECH (1)",
    val audioTrackPlaybackState: String = "PLAYSTATE_STOPPED (1)",
    val directTtsFallbackUsed: Boolean = false,
    val effectivePcmGain: Float = 1.0f,
    val audioTrackStreamGain: Float = 1.0f,
    val systemMediaVolumePercent: Int? = null,
    val errorOrLimitation: String = "None"
)

/**
 * Real-time state exposed to the Audio Diagnostics Screen.
 */
data class AudioDiagnosticsState(
    val selectedVoiceLabel: String = "Hindi Female • hi_IN-priyamvada-medium",
    val selectedTtsEnginePackage: String = "Default System TTS",
    val playbackEngineLabel: String = "SynthesizeToFile -> AudioTrack PCM (Direct TTS Disabled)",
    val independentVolumeEnabled: Boolean = true,
    val independentVolumePercent: Int = 100,
    val effectiveGainValue: Float = 1.0f,
    val audioTrackStreamGainValue: Float = 1.0f,
    val playbackState: String = "Idle",
    val audioFocusState: String = "Idle (No Active Focus Request)",
    val mediaDuckingLevel: String = "MEDIUM",
    val outputDeviceName: String = "N/A",
    val currentPriorityLabel: String = "None",
    val queueSize: Int = 0,
    val queueSummary: String = "Queue Empty (0 pending)",
    val callOrAlarmState: String = "Normal (No Call/Alarm Active)",
    val lastPlaybackError: String = "None",
    // Trace & Routing Verification Fields
    val lastTraceEntryPoint: String = "None (Run Test Voice or Connect Charger)",
    val synthesizeToFileSucceeded: Boolean? = null,
    val pcmWavDecoded: Boolean? = null,
    val decodedWavFormatSummary: String = "N/A",
    val audioTrackCreated: Boolean? = null,
    val actualAudioAttributesUsage: String = "USAGE_ASSISTANCE_ACCESSIBILITY (11)",
    val actualAudioAttributesContentType: String = "CONTENT_TYPE_SPEECH (1)",
    val audioTrackPlaybackState: String = "Idle",
    val directTtsFallbackUsed: Boolean = false,
    val systemMediaVolumePercent: Int? = null,
    val isAccessibilityServiceEnabledOnOs: Boolean = false,
    val osStreamRoutingLimitationNote: String = "Awaiting hardware audio routing inspection"
)

/**
 * Four independent neural/offline voice profiles for Battery Boss.
 */
enum class VoiceProfilePreset(
    val id: String,
    val title: String,
    val personaName: String,
    val subtitle: String,
    val voiceDescription: String,
    val language: VoiceLanguage,
    val gender: VoiceGender,
    val defaultPitch: Float,
    val defaultSpeed: Float,
    val piperModelId: String,
    val samplePhrase: String
) {
    HINDI_FEMALE(
        id = "hindi_female",
        title = "Hindi Female",
        personaName = "Priyamvada (प्रियम्वदा)",
        subtitle = "hi_IN-priyamvada-medium • Natural Indian Hindi Female",
        voiceDescription = "Warm, expressive Indian Hindi female neural prosody with natural breath pauses.",
        language = VoiceLanguage.HINDI,
        gender = VoiceGender.FEMALE,
        defaultPitch = 1.04f,
        defaultSpeed = 0.96f,
        piperModelId = "hi_IN-priyamvada-medium",
        samplePhrase = "नमस्ते बॉस! मैं प्रियम्वदा हूँ। चार्जर लग गया है बॉस। अब बैटरी चार्ज हो रही है।"
    ),
    HINDI_MALE(
        id = "hindi_male",
        title = "Hindi Male",
        personaName = "Rohan (रोहन)",
        subtitle = "hi_IN-rohan-medium • Deep Confident Indian Hindi Male",
        voiceDescription = "Deep, resonant Indian Hindi male voice tuned for clear Boss status briefings.",
        language = VoiceLanguage.HINDI,
        gender = VoiceGender.MALE,
        defaultPitch = 0.84f,
        defaultSpeed = 0.95f,
        piperModelId = "hi_IN-rohan-medium",
        samplePhrase = "नमस्ते बॉस! मैं रोहन हूँ। बॉस, अब बैटरी आधी चार्ज हो चुकी है।"
    ),
    ENGLISH_FEMALE(
        id = "english_female",
        title = "English Female",
        personaName = "Lessac / Prerana (English)",
        subtitle = "en_US-lessac-medium • Natural Indian/US English Female",
        voiceDescription = "Crisp, natural English female articulation with smooth sentence cadence.",
        language = VoiceLanguage.ENGLISH,
        gender = VoiceGender.FEMALE,
        defaultPitch = 1.03f,
        defaultSpeed = 0.97f,
        piperModelId = "en_US-lessac-medium",
        samplePhrase = "Hello Boss! Charger connected, Boss. Battery is now charging smoothly."
    ),
    ENGLISH_MALE(
        id = "english_male",
        title = "English Male",
        personaName = "Ryan / Prabhat (English)",
        subtitle = "en_US-ryan-medium • Deep Natural English Male",
        voiceDescription = "Authoritative, calm English male voice with balanced low-frequency resonance.",
        language = VoiceLanguage.ENGLISH,
        gender = VoiceGender.MALE,
        defaultPitch = 0.82f,
        defaultSpeed = 0.96f,
        piperModelId = "en_US-ryan-medium",
        samplePhrase = "Hello Boss! Boss, battery is fully charged. You can unplug the charger now."
    )
}

enum class VoiceModelState(val label: String) {
    BUNDLED_READY("Offline Verified"),
    INSTALLED_LOCAL("Installed Offline"),
    INSTALLING("Verifying Model..."),
    SYSTEM_VOICE_MISSING("System Pack Required")
}

/**
 * Metadata and licensing for offline Sherpa-ONNX / Piper neural models and Android offline TTS packs.
 */
data class OfflineVoiceModelPack(
    val preset: VoiceProfilePreset,
    val modelId: String,
    val displayName: String,
    val language: VoiceLanguage,
    val gender: VoiceGender,
    val engineArchitecture: String,
    val sampleRateHz: Int,
    val sizeMb: Float,
    val licenseName: String,
    val licenseAttribution: String,
    val state: VoiceModelState,
    val systemOfflineVoiceMatched: String? = null,
    val isTrueGenderMatchedVoice: Boolean = false
)

/**
 * Audio sample scenario for the Voice Studio Comparison Matrix.
 */
data class VoiceSampleScenario(
    val id: String,
    val title: String,
    val badge: String,
    val hindiText: String,
    val englishText: String
)

/**
 * Conversational Smart Boss default Hindi and English phrases.
 */
object DefaultBossMessages {
    const val HI_CHARGER_CONNECTED = "चार्जर लग गया है बॉस। अब बैटरी चार्ज हो रही है।"
    const val HI_BATTERY_20 = "बॉस, बैटरी 20 प्रतिशत हो गई है।"
    const val HI_BATTERY_50 = "बॉस, अब बैटरी आधी चार्ज हो चुकी है।"
    const val HI_BATTERY_80 = "बॉस, बैटरी 80 प्रतिशत तक पहुँच गई है।"
    const val HI_BATTERY_FULL = "बॉस, बैटरी फुल हो गई है। अब चार्जर निकाल सकते हैं।"
    const val HI_HIGH_TEMP = "बॉस, मोबाइल थोड़ा ज्यादा गर्म हो रहा है।"
    const val HI_CHARGER_DISCONNECTED = "बॉस, चार्जर निकाल दिया गया है।"
    const val HI_BATTERY_INTERVAL = "बॉस, बैटरी {level} प्रतिशत चार्ज हो गई है।"
    const val HI_LOW_BATTERY = "बॉस, बैटरी कम है, अभी {level} प्रतिशत बची है।"

    const val EN_CHARGER_CONNECTED = "Charger connected, Boss. Battery is now charging."
    const val EN_BATTERY_20 = "Boss, battery has reached 20 percent."
    const val EN_BATTERY_50 = "Boss, battery is now half charged at 50 percent."
    const val EN_BATTERY_80 = "Boss, battery has reached 80 percent."
    const val EN_BATTERY_FULL = "Boss, battery is fully charged. You can unplug the charger now."
    const val EN_HIGH_TEMP = "Boss, the phone is getting a bit too warm."
    const val EN_CHARGER_DISCONNECTED = "Boss, the charger has been unplugged."
    const val EN_BATTERY_INTERVAL = "Boss, battery is {level} percent charged."
    const val EN_LOW_BATTERY = "Boss, battery is low at {level} percent."

    val comparisonScenarios: List<VoiceSampleScenario> = listOf(
        VoiceSampleScenario(
            id = "connected",
            title = "Charger Connected",
            badge = "PLUG IN",
            hindiText = HI_CHARGER_CONNECTED,
            englishText = EN_CHARGER_CONNECTED
        ),
        VoiceSampleScenario(
            id = "milestone_20",
            title = "Battery 20% Milestone",
            badge = "20%",
            hindiText = HI_BATTERY_20,
            englishText = EN_BATTERY_20
        ),
        VoiceSampleScenario(
            id = "milestone_50",
            title = "Battery 50% Halfway",
            badge = "50%",
            hindiText = HI_BATTERY_50,
            englishText = EN_BATTERY_50
        ),
        VoiceSampleScenario(
            id = "milestone_80",
            title = "Battery 80% Milestone",
            badge = "80%",
            hindiText = HI_BATTERY_80,
            englishText = EN_BATTERY_80
        ),
        VoiceSampleScenario(
            id = "full_100",
            title = "Battery 100% Full",
            badge = "100%",
            hindiText = HI_BATTERY_FULL,
            englishText = EN_BATTERY_FULL
        ),
        VoiceSampleScenario(
            id = "high_temp",
            title = "High Temperature Alert",
            badge = "THERMAL",
            hindiText = HI_HIGH_TEMP,
            englishText = EN_HIGH_TEMP
        ),
        VoiceSampleScenario(
            id = "disconnected",
            title = "Charger Disconnected",
            badge = "UNPLUG",
            hindiText = HI_CHARGER_DISCONNECTED,
            englishText = EN_CHARGER_DISCONNECTED
        )
    )
}

/**
 * Complete user settings persisted via DataStore.
 */
data class UserSettings(
    // Voice Settings
    val language: VoiceLanguage = VoiceLanguage.HINDI,
    val gender: VoiceGender = VoiceGender.FEMALE,
    val voiceSpeed: Float = 0.96f,
    val voicePitch: Float = 1.04f,
    val voiceVolume: Float = 1.0f, // Default 1.0f (100%) for every fresh installation
    val lastRememberedVoiceVolume: Float = 1.0f,
    val independentVolumeEnabled: Boolean = true,
    val rememberLastVoiceVolume: Boolean = true,
    val applyVolumeToAllAnnouncements: Boolean = true,
    val mediaDuckingLevel: MediaDuckingLevel = MediaDuckingLevel.MEDIUM,
    val repeatPolicy: AnnouncementRepeatPolicy = AnnouncementRepeatPolicy.NONE,
    val selectedModelId: String = VoiceProfilePreset.HINDI_FEMALE.piperModelId,
    val naturalProsodyEnabled: Boolean = true,

    // Conversational Smart Boss Messages (Hindi)
    val msgHiConnected: String = DefaultBossMessages.HI_CHARGER_CONNECTED,
    val msgHiDisconnected: String = DefaultBossMessages.HI_CHARGER_DISCONNECTED,
    val msgHi20Percent: String = DefaultBossMessages.HI_BATTERY_20,
    val msgHi50Percent: String = DefaultBossMessages.HI_BATTERY_50,
    val msgHi80Percent: String = DefaultBossMessages.HI_BATTERY_80,
    val msgHiInterval: String = DefaultBossMessages.HI_BATTERY_INTERVAL,
    val msgHiFull: String = DefaultBossMessages.HI_BATTERY_FULL,
    val msgHiLowBattery: String = DefaultBossMessages.HI_LOW_BATTERY,
    val msgHiHighTemp: String = DefaultBossMessages.HI_HIGH_TEMP,

    // Conversational Smart Boss Messages (English)
    val msgEnConnected: String = DefaultBossMessages.EN_CHARGER_CONNECTED,
    val msgEnDisconnected: String = DefaultBossMessages.EN_CHARGER_DISCONNECTED,
    val msgEn20Percent: String = DefaultBossMessages.EN_BATTERY_20,
    val msgEn50Percent: String = DefaultBossMessages.EN_BATTERY_50,
    val msgEn80Percent: String = DefaultBossMessages.EN_BATTERY_80,
    val msgEnInterval: String = DefaultBossMessages.EN_BATTERY_INTERVAL,
    val msgEnFull: String = DefaultBossMessages.EN_BATTERY_FULL,
    val msgEnLowBattery: String = DefaultBossMessages.EN_LOW_BATTERY,
    val msgEnHighTemp: String = DefaultBossMessages.EN_HIGH_TEMP,

    // Charging Announcement Triggers
    val announceIntervalPercent: Int = 10, // 5, 10, 15, 20, or custom (1..50)
    val announceIntervalEnabled: Boolean = true,
    val announceSmartMilestones: Boolean = true, // 20%, 50%, 80% conversational alerts
    val announceFullCharge: Boolean = true,
    val announceChargerConnected: Boolean = true,
    val announceChargerDisconnected: Boolean = true,
    val announceLowBattery: Boolean = true,
    val lowBatteryThreshold: Int = 20,
    val announceTemperatureWarning: Boolean = true,
    val temperatureWarningCelsius: Int = 42,

    // Quiet Hours & Ringer Mode Controls
    val quietHoursEnabled: Boolean = false,
    val quietStartHour: Int = 23, // 11:00 PM
    val quietEndHour: Int = 7,    // 7:00 AM
    val respectSystemRinger: Boolean = false,

    // Background & Service Settings
    val backgroundMonitoringEnabled: Boolean = true,
    val startOnBootEnabled: Boolean = true,
    val persistentNotificationEnabled: Boolean = true,
    val installedModelIds: Set<String> = setOf(
        VoiceProfilePreset.HINDI_FEMALE.piperModelId,
        VoiceProfilePreset.HINDI_MALE.piperModelId,
        VoiceProfilePreset.ENGLISH_FEMALE.piperModelId,
        VoiceProfilePreset.ENGLISH_MALE.piperModelId
    )
) {
    val activePreset: VoiceProfilePreset
        get() = when {
            language == VoiceLanguage.HINDI && gender == VoiceGender.FEMALE -> VoiceProfilePreset.HINDI_FEMALE
            language == VoiceLanguage.HINDI && gender == VoiceGender.MALE -> VoiceProfilePreset.HINDI_MALE
            language == VoiceLanguage.ENGLISH && gender == VoiceGender.FEMALE -> VoiceProfilePreset.ENGLISH_FEMALE
            else -> VoiceProfilePreset.ENGLISH_MALE
        }
}

/**
 * OEM-specific background execution guidance based on Build.MANUFACTURER.
 */
data class OemOptimizationGuide(
    val manufacturerName: String,
    val osSkinName: String,
    val steps: List<String>,
    val caveatNote: String
)
