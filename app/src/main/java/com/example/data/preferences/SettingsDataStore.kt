package com.example.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.model.AnnouncementRepeatPolicy
import com.example.model.DefaultBossMessages
import com.example.model.MediaDuckingLevel
import com.example.model.UserSettings
import com.example.model.VoiceGender
import com.example.model.VoiceLanguage
import com.example.model.VoiceProfilePreset
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "battery_boss_settings")

/**
 * VoiceSettingsRepository & DataStore persistence layer for Battery Boss.
 */
class SettingsDataStore(private val context: Context) {

    private object Keys {
        val LANGUAGE = stringPreferencesKey("voice_language")
        val GENDER = stringPreferencesKey("voice_gender")
        val VOICE_SPEED = floatPreferencesKey("voice_speed")
        val VOICE_PITCH = floatPreferencesKey("voice_pitch")
        val VOICE_VOLUME = floatPreferencesKey("voice_volume_independent_v2")
        val LAST_REMEMBERED_VOLUME = floatPreferencesKey("last_remembered_voice_volume_v2")
        val INDEPENDENT_VOLUME_ENABLED = booleanPreferencesKey("independent_volume_enabled")
        val REMEMBER_LAST_VOLUME = booleanPreferencesKey("remember_last_voice_volume")
        val APPLY_VOLUME_ALL = booleanPreferencesKey("apply_volume_to_all_announcements")
        val MEDIA_DUCKING_LEVEL = stringPreferencesKey("media_ducking_level")
        val REPEAT_POLICY = stringPreferencesKey("announcement_repeat_policy")
        val SELECTED_MODEL_ID = stringPreferencesKey("selected_model_id_v3")
        val INSTALLED_MODELS = stringSetPreferencesKey("installed_model_ids_v3")
        val NATURAL_PROSODY = booleanPreferencesKey("natural_prosody_enabled")

        val MSG_HI_CONNECTED = stringPreferencesKey("msg_hi_connected_v2")
        val MSG_HI_DISCONNECTED = stringPreferencesKey("msg_hi_disconnected_v2")
        val MSG_HI_20 = stringPreferencesKey("msg_hi_20_v2")
        val MSG_HI_50 = stringPreferencesKey("msg_hi_50_v2")
        val MSG_HI_80 = stringPreferencesKey("msg_hi_80_v2")
        val MSG_HI_INTERVAL = stringPreferencesKey("msg_hi_interval_v2")
        val MSG_HI_FULL = stringPreferencesKey("msg_hi_full_v2")
        val MSG_HI_LOW_BATTERY = stringPreferencesKey("msg_hi_low_battery_v2")
        val MSG_HI_HIGH_TEMP = stringPreferencesKey("msg_hi_high_temp_v2")

        val MSG_EN_CONNECTED = stringPreferencesKey("msg_en_connected_v2")
        val MSG_EN_DISCONNECTED = stringPreferencesKey("msg_en_disconnected_v2")
        val MSG_EN_20 = stringPreferencesKey("msg_en_20_v2")
        val MSG_EN_50 = stringPreferencesKey("msg_en_50_v2")
        val MSG_EN_80 = stringPreferencesKey("msg_en_80_v2")
        val MSG_EN_INTERVAL = stringPreferencesKey("msg_en_interval_v2")
        val MSG_EN_FULL = stringPreferencesKey("msg_en_full_v2")
        val MSG_EN_LOW_BATTERY = stringPreferencesKey("msg_en_low_battery_v2")
        val MSG_EN_HIGH_TEMP = stringPreferencesKey("msg_en_high_temp_v2")

        val ANNOUNCE_INTERVAL_PERCENT = intPreferencesKey("announce_interval_percent")
        val ANNOUNCE_INTERVAL_ENABLED = booleanPreferencesKey("announce_interval_enabled")
        val ANNOUNCE_SMART_MILESTONES = booleanPreferencesKey("announce_smart_milestones")
        val ANNOUNCE_FULL_CHARGE = booleanPreferencesKey("announce_full_charge")
        val ANNOUNCE_CHARGER_CONNECTED = booleanPreferencesKey("announce_charger_connected")
        val ANNOUNCE_CHARGER_DISCONNECTED = booleanPreferencesKey("announce_charger_disconnected")
        val ANNOUNCE_LOW_BATTERY = booleanPreferencesKey("announce_low_battery")
        val LOW_BATTERY_THRESHOLD = intPreferencesKey("low_battery_threshold")
        val ANNOUNCE_TEMP_WARNING = booleanPreferencesKey("announce_temp_warning")
        val TEMP_WARNING_CELSIUS = intPreferencesKey("temp_warning_celsius")

        val QUIET_HOURS_ENABLED = booleanPreferencesKey("quiet_hours_enabled")
        val QUIET_START_HOUR = intPreferencesKey("quiet_start_hour")
        val QUIET_END_HOUR = intPreferencesKey("quiet_end_hour")
        val RESPECT_SYSTEM_RINGER = booleanPreferencesKey("respect_system_ringer")

        val BACKGROUND_MONITORING = booleanPreferencesKey("background_monitoring_enabled")
        val START_ON_BOOT = booleanPreferencesKey("start_on_boot_enabled")
        val PERSISTENT_NOTIFICATION = booleanPreferencesKey("persistent_notification_enabled")
    }

    val settingsFlow: Flow<UserSettings> = context.dataStore.data.map { prefs ->
        val languageName = prefs[Keys.LANGUAGE] ?: VoiceLanguage.HINDI.name
        val genderName = prefs[Keys.GENDER] ?: VoiceGender.FEMALE.name
        val language = runCatching { VoiceLanguage.valueOf(languageName) }.getOrDefault(VoiceLanguage.HINDI)
        val gender = runCatching { VoiceGender.valueOf(genderName) }.getOrDefault(VoiceGender.FEMALE)

        val defaultPreset = when {
            language == VoiceLanguage.HINDI && gender == VoiceGender.FEMALE -> VoiceProfilePreset.HINDI_FEMALE
            language == VoiceLanguage.HINDI && gender == VoiceGender.MALE -> VoiceProfilePreset.HINDI_MALE
            language == VoiceLanguage.ENGLISH && gender == VoiceGender.FEMALE -> VoiceProfilePreset.ENGLISH_FEMALE
            else -> VoiceProfilePreset.ENGLISH_MALE
        }

        val rememberVolume = prefs[Keys.REMEMBER_LAST_VOLUME] ?: true
        val savedVolume = (prefs[Keys.VOICE_VOLUME] ?: 1.0f).coerceIn(0.0f, 1.0f)
        val effectiveVolume = if (rememberVolume) savedVolume else 1.0f

        val duckingName = prefs[Keys.MEDIA_DUCKING_LEVEL] ?: MediaDuckingLevel.MEDIUM.name
        val duckingLevel = runCatching { MediaDuckingLevel.valueOf(duckingName) }.getOrDefault(MediaDuckingLevel.MEDIUM)

        val repeatName = prefs[Keys.REPEAT_POLICY] ?: AnnouncementRepeatPolicy.NONE.name
        val repeatPolicy = runCatching { AnnouncementRepeatPolicy.valueOf(repeatName) }.getOrDefault(AnnouncementRepeatPolicy.NONE)

        UserSettings(
            language = language,
            gender = gender,
            voiceSpeed = prefs[Keys.VOICE_SPEED] ?: defaultPreset.defaultSpeed,
            voicePitch = prefs[Keys.VOICE_PITCH] ?: defaultPreset.defaultPitch,
            voiceVolume = effectiveVolume,
            lastRememberedVoiceVolume = (prefs[Keys.LAST_REMEMBERED_VOLUME] ?: savedVolume).coerceIn(0.0f, 1.0f),
            independentVolumeEnabled = prefs[Keys.INDEPENDENT_VOLUME_ENABLED] ?: true,
            rememberLastVoiceVolume = rememberVolume,
            applyVolumeToAllAnnouncements = prefs[Keys.APPLY_VOLUME_ALL] ?: true,
            mediaDuckingLevel = duckingLevel,
            repeatPolicy = repeatPolicy,
            selectedModelId = prefs[Keys.SELECTED_MODEL_ID] ?: defaultPreset.piperModelId,
            naturalProsodyEnabled = prefs[Keys.NATURAL_PROSODY] ?: true,
            installedModelIds = prefs[Keys.INSTALLED_MODELS] ?: setOf(
                VoiceProfilePreset.HINDI_FEMALE.piperModelId,
                VoiceProfilePreset.HINDI_MALE.piperModelId,
                VoiceProfilePreset.ENGLISH_FEMALE.piperModelId,
                VoiceProfilePreset.ENGLISH_MALE.piperModelId
            ),
            msgHiConnected = prefs[Keys.MSG_HI_CONNECTED] ?: DefaultBossMessages.HI_CHARGER_CONNECTED,
            msgHiDisconnected = prefs[Keys.MSG_HI_DISCONNECTED] ?: DefaultBossMessages.HI_CHARGER_DISCONNECTED,
            msgHi20Percent = prefs[Keys.MSG_HI_20] ?: DefaultBossMessages.HI_BATTERY_20,
            msgHi50Percent = prefs[Keys.MSG_HI_50] ?: DefaultBossMessages.HI_BATTERY_50,
            msgHi80Percent = prefs[Keys.MSG_HI_80] ?: DefaultBossMessages.HI_BATTERY_80,
            msgHiInterval = prefs[Keys.MSG_HI_INTERVAL] ?: DefaultBossMessages.HI_BATTERY_INTERVAL,
            msgHiFull = prefs[Keys.MSG_HI_FULL] ?: DefaultBossMessages.HI_BATTERY_FULL,
            msgHiLowBattery = prefs[Keys.MSG_HI_LOW_BATTERY] ?: DefaultBossMessages.HI_LOW_BATTERY,
            msgHiHighTemp = prefs[Keys.MSG_HI_HIGH_TEMP] ?: DefaultBossMessages.HI_HIGH_TEMP,

            msgEnConnected = prefs[Keys.MSG_EN_CONNECTED] ?: DefaultBossMessages.EN_CHARGER_CONNECTED,
            msgEnDisconnected = prefs[Keys.MSG_EN_DISCONNECTED] ?: DefaultBossMessages.EN_CHARGER_DISCONNECTED,
            msgEn20Percent = prefs[Keys.MSG_EN_20] ?: DefaultBossMessages.EN_BATTERY_20,
            msgEn50Percent = prefs[Keys.MSG_EN_50] ?: DefaultBossMessages.EN_BATTERY_50,
            msgEn80Percent = prefs[Keys.MSG_EN_80] ?: DefaultBossMessages.EN_BATTERY_80,
            msgEnInterval = prefs[Keys.MSG_EN_INTERVAL] ?: DefaultBossMessages.EN_BATTERY_INTERVAL,
            msgEnFull = prefs[Keys.MSG_EN_FULL] ?: DefaultBossMessages.EN_BATTERY_FULL,
            msgEnLowBattery = prefs[Keys.MSG_EN_LOW_BATTERY] ?: DefaultBossMessages.EN_LOW_BATTERY,
            msgEnHighTemp = prefs[Keys.MSG_EN_HIGH_TEMP] ?: DefaultBossMessages.EN_HIGH_TEMP,

            announceIntervalPercent = (prefs[Keys.ANNOUNCE_INTERVAL_PERCENT] ?: 10).coerceIn(1, 50),
            announceIntervalEnabled = prefs[Keys.ANNOUNCE_INTERVAL_ENABLED] ?: true,
            announceSmartMilestones = prefs[Keys.ANNOUNCE_SMART_MILESTONES] ?: true,
            announceFullCharge = prefs[Keys.ANNOUNCE_FULL_CHARGE] ?: true,
            announceChargerConnected = prefs[Keys.ANNOUNCE_CHARGER_CONNECTED] ?: true,
            announceChargerDisconnected = prefs[Keys.ANNOUNCE_CHARGER_DISCONNECTED] ?: true,
            announceLowBattery = prefs[Keys.ANNOUNCE_LOW_BATTERY] ?: true,
            lowBatteryThreshold = (prefs[Keys.LOW_BATTERY_THRESHOLD] ?: 20).coerceIn(5, 40),
            announceTemperatureWarning = prefs[Keys.ANNOUNCE_TEMP_WARNING] ?: true,
            temperatureWarningCelsius = (prefs[Keys.TEMP_WARNING_CELSIUS] ?: 42).coerceIn(35, 55),

            quietHoursEnabled = prefs[Keys.QUIET_HOURS_ENABLED] ?: false,
            quietStartHour = (prefs[Keys.QUIET_START_HOUR] ?: 23).coerceIn(0, 23),
            quietEndHour = (prefs[Keys.QUIET_END_HOUR] ?: 7).coerceIn(0, 23),
            respectSystemRinger = prefs[Keys.RESPECT_SYSTEM_RINGER] ?: false,

            backgroundMonitoringEnabled = prefs[Keys.BACKGROUND_MONITORING] ?: true,
            startOnBootEnabled = prefs[Keys.START_ON_BOOT] ?: true,
            persistentNotificationEnabled = prefs[Keys.PERSISTENT_NOTIFICATION] ?: true
        )
    }

    suspend fun getSettingsSnapshot(): UserSettings = settingsFlow.first()

    suspend fun selectVoicePreset(preset: VoiceProfilePreset) {
        context.dataStore.edit { prefs ->
            prefs[Keys.LANGUAGE] = preset.language.name
            prefs[Keys.GENDER] = preset.gender.name
            prefs[Keys.VOICE_PITCH] = preset.defaultPitch
            prefs[Keys.VOICE_SPEED] = preset.defaultSpeed
            prefs[Keys.SELECTED_MODEL_ID] = preset.piperModelId
        }
    }

    suspend fun updateLanguage(language: VoiceLanguage) {
        val current = getSettingsSnapshot()
        val matchingPreset = VoiceProfilePreset.entries.first {
            it.language == language && it.gender == current.gender
        }
        selectVoicePreset(matchingPreset)
    }

    suspend fun updateGender(gender: VoiceGender) {
        val current = getSettingsSnapshot()
        val matchingPreset = VoiceProfilePreset.entries.first {
            it.language == current.language && it.gender == gender
        }
        selectVoicePreset(matchingPreset)
    }

    suspend fun updateVoiceParameters(speed: Float, pitch: Float, volume: Float, naturalProsody: Boolean? = null) {
        val clampedVol = volume.coerceIn(0.0f, 1.0f)
        context.dataStore.edit { prefs ->
            prefs[Keys.VOICE_SPEED] = speed.coerceIn(0.5f, 2.0f)
            prefs[Keys.VOICE_PITCH] = pitch.coerceIn(0.5f, 2.0f)
            prefs[Keys.VOICE_VOLUME] = clampedVol
            if (clampedVol > 0.0f) {
                prefs[Keys.LAST_REMEMBERED_VOLUME] = clampedVol
            }
            naturalProsody?.let { prefs[Keys.NATURAL_PROSODY] = it }
        }
    }

    suspend fun updateIndependentVolumeSettings(
        independentEnabled: Boolean? = null,
        volume: Float? = null,
        rememberLastVolume: Boolean? = null,
        applyToAllAnnouncements: Boolean? = null,
        mediaDuckingLevel: MediaDuckingLevel? = null,
        repeatPolicy: AnnouncementRepeatPolicy? = null
    ) {
        context.dataStore.edit { prefs ->
            independentEnabled?.let { prefs[Keys.INDEPENDENT_VOLUME_ENABLED] = it }
            volume?.let { v ->
                val clamped = v.coerceIn(0.0f, 1.0f)
                prefs[Keys.VOICE_VOLUME] = clamped
                if (clamped > 0.0f) {
                    prefs[Keys.LAST_REMEMBERED_VOLUME] = clamped
                }
            }
            rememberLastVolume?.let { prefs[Keys.REMEMBER_LAST_VOLUME] = it }
            applyToAllAnnouncements?.let { prefs[Keys.APPLY_VOLUME_ALL] = it }
            mediaDuckingLevel?.let { prefs[Keys.MEDIA_DUCKING_LEVEL] = it.name }
            repeatPolicy?.let { prefs[Keys.REPEAT_POLICY] = it.name }
        }
    }

    suspend fun updateCustomMessages(
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
        context.dataStore.edit { prefs ->
            if (isHindi) {
                prefs[Keys.MSG_HI_CONNECTED] = connected.ifBlank { DefaultBossMessages.HI_CHARGER_CONNECTED }
                prefs[Keys.MSG_HI_DISCONNECTED] = disconnected.ifBlank { DefaultBossMessages.HI_CHARGER_DISCONNECTED }
                prefs[Keys.MSG_HI_20] = milestone20.ifBlank { DefaultBossMessages.HI_BATTERY_20 }
                prefs[Keys.MSG_HI_50] = milestone50.ifBlank { DefaultBossMessages.HI_BATTERY_50 }
                prefs[Keys.MSG_HI_80] = milestone80.ifBlank { DefaultBossMessages.HI_BATTERY_80 }
                prefs[Keys.MSG_HI_INTERVAL] = interval.ifBlank { DefaultBossMessages.HI_BATTERY_INTERVAL }
                prefs[Keys.MSG_HI_FULL] = full.ifBlank { DefaultBossMessages.HI_BATTERY_FULL }
                prefs[Keys.MSG_HI_LOW_BATTERY] = lowBattery.ifBlank { DefaultBossMessages.HI_LOW_BATTERY }
                prefs[Keys.MSG_HI_HIGH_TEMP] = highTemp.ifBlank { DefaultBossMessages.HI_HIGH_TEMP }
            } else {
                prefs[Keys.MSG_EN_CONNECTED] = connected.ifBlank { DefaultBossMessages.EN_CHARGER_CONNECTED }
                prefs[Keys.MSG_EN_DISCONNECTED] = disconnected.ifBlank { DefaultBossMessages.EN_CHARGER_DISCONNECTED }
                prefs[Keys.MSG_EN_20] = milestone20.ifBlank { DefaultBossMessages.EN_BATTERY_20 }
                prefs[Keys.MSG_EN_50] = milestone50.ifBlank { DefaultBossMessages.EN_BATTERY_50 }
                prefs[Keys.MSG_EN_80] = milestone80.ifBlank { DefaultBossMessages.EN_BATTERY_80 }
                prefs[Keys.MSG_EN_INTERVAL] = interval.ifBlank { DefaultBossMessages.EN_BATTERY_INTERVAL }
                prefs[Keys.MSG_EN_FULL] = full.ifBlank { DefaultBossMessages.EN_BATTERY_FULL }
                prefs[Keys.MSG_EN_LOW_BATTERY] = lowBattery.ifBlank { DefaultBossMessages.EN_LOW_BATTERY }
                prefs[Keys.MSG_EN_HIGH_TEMP] = highTemp.ifBlank { DefaultBossMessages.EN_HIGH_TEMP }
            }
        }
    }

    suspend fun resetCustomMessagesToDefault() {
        context.dataStore.edit { prefs ->
            prefs[Keys.MSG_HI_CONNECTED] = DefaultBossMessages.HI_CHARGER_CONNECTED
            prefs[Keys.MSG_HI_DISCONNECTED] = DefaultBossMessages.HI_CHARGER_DISCONNECTED
            prefs[Keys.MSG_HI_20] = DefaultBossMessages.HI_BATTERY_20
            prefs[Keys.MSG_HI_50] = DefaultBossMessages.HI_BATTERY_50
            prefs[Keys.MSG_HI_80] = DefaultBossMessages.HI_BATTERY_80
            prefs[Keys.MSG_HI_INTERVAL] = DefaultBossMessages.HI_BATTERY_INTERVAL
            prefs[Keys.MSG_HI_FULL] = DefaultBossMessages.HI_BATTERY_FULL
            prefs[Keys.MSG_HI_LOW_BATTERY] = DefaultBossMessages.HI_LOW_BATTERY
            prefs[Keys.MSG_HI_HIGH_TEMP] = DefaultBossMessages.HI_HIGH_TEMP

            prefs[Keys.MSG_EN_CONNECTED] = DefaultBossMessages.EN_CHARGER_CONNECTED
            prefs[Keys.MSG_EN_DISCONNECTED] = DefaultBossMessages.EN_CHARGER_DISCONNECTED
            prefs[Keys.MSG_EN_20] = DefaultBossMessages.EN_BATTERY_20
            prefs[Keys.MSG_EN_50] = DefaultBossMessages.EN_BATTERY_50
            prefs[Keys.MSG_EN_80] = DefaultBossMessages.EN_BATTERY_80
            prefs[Keys.MSG_EN_INTERVAL] = DefaultBossMessages.EN_BATTERY_INTERVAL
            prefs[Keys.MSG_EN_FULL] = DefaultBossMessages.EN_BATTERY_FULL
            prefs[Keys.MSG_EN_LOW_BATTERY] = DefaultBossMessages.EN_LOW_BATTERY
            prefs[Keys.MSG_EN_HIGH_TEMP] = DefaultBossMessages.EN_HIGH_TEMP
        }
    }

    suspend fun updateChargingAlerts(
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
        context.dataStore.edit { prefs ->
            intervalPercent?.let { prefs[Keys.ANNOUNCE_INTERVAL_PERCENT] = it.coerceIn(1, 50) }
            intervalEnabled?.let { prefs[Keys.ANNOUNCE_INTERVAL_ENABLED] = it }
            smartMilestones?.let { prefs[Keys.ANNOUNCE_SMART_MILESTONES] = it }
            fullCharge?.let { prefs[Keys.ANNOUNCE_FULL_CHARGE] = it }
            chargerConnected?.let { prefs[Keys.ANNOUNCE_CHARGER_CONNECTED] = it }
            chargerDisconnected?.let { prefs[Keys.ANNOUNCE_CHARGER_DISCONNECTED] = it }
            lowBattery?.let { prefs[Keys.ANNOUNCE_LOW_BATTERY] = it }
            lowBatteryThreshold?.let { prefs[Keys.LOW_BATTERY_THRESHOLD] = it.coerceIn(5, 40) }
            tempWarning?.let { prefs[Keys.ANNOUNCE_TEMP_WARNING] = it }
            tempWarningCelsius?.let { prefs[Keys.TEMP_WARNING_CELSIUS] = it.coerceIn(35, 55) }
        }
    }

    suspend fun updateQuietHoursAndRinger(
        quietHoursEnabled: Boolean? = null,
        quietStartHour: Int? = null,
        quietEndHour: Int? = null,
        respectSystemRinger: Boolean? = null
    ) {
        context.dataStore.edit { prefs ->
            quietHoursEnabled?.let { prefs[Keys.QUIET_HOURS_ENABLED] = it }
            quietStartHour?.let { prefs[Keys.QUIET_START_HOUR] = it.coerceIn(0, 23) }
            quietEndHour?.let { prefs[Keys.QUIET_END_HOUR] = it.coerceIn(0, 23) }
            respectSystemRinger?.let { prefs[Keys.RESPECT_SYSTEM_RINGER] = it }
        }
    }

    suspend fun updateBackgroundSettings(
        backgroundMonitoring: Boolean? = null,
        startOnBoot: Boolean? = null,
        persistentNotification: Boolean? = null
    ) {
        context.dataStore.edit { prefs ->
            backgroundMonitoring?.let { prefs[Keys.BACKGROUND_MONITORING] = it }
            startOnBoot?.let { prefs[Keys.START_ON_BOOT] = it }
            persistentNotification?.let { prefs[Keys.PERSISTENT_NOTIFICATION] = it }
        }
    }

    suspend fun markOfflineModelInstalled(modelId: String) {
        context.dataStore.edit { prefs ->
            val current = prefs[Keys.INSTALLED_MODELS] ?: emptySet()
            prefs[Keys.INSTALLED_MODELS] = current + modelId
            prefs[Keys.SELECTED_MODEL_ID] = modelId
        }
    }
}
