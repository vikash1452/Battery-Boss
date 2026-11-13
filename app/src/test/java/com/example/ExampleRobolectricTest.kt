package com.example

import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.example.audio.IndependentVoiceController
import com.example.audio.QueuedVoiceAnnouncement
import com.example.hardware.BatteryHardwareReader
import com.example.model.AnnouncementPriority
import com.example.model.ChargingSource
import com.example.model.ChargingSpeedTier
import com.example.model.DefaultBossMessages
import com.example.model.MetricConfidence
import com.example.model.UserSettings
import com.example.model.VoiceLanguage
import com.example.model.VoiceProfilePreset
import com.example.model.VoiceVolumePreset
import com.example.voice.ProsodyAndPhoneticsEngine
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.PriorityBlockingQueue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun `read app_name from context matches Battery Boss`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("Battery Boss", appName)
    }

    @Test
    fun `all four independent voice profiles, exact neural model IDs and Smart Boss 2_0 phrases match spec`() {
        val presets = VoiceProfilePreset.entries
        assertEquals(4, presets.size)
        assertEquals("hi_IN-priyamvada-medium", VoiceProfilePreset.HINDI_FEMALE.piperModelId)
        assertEquals("hi_IN-rohan-medium", VoiceProfilePreset.HINDI_MALE.piperModelId)
        assertEquals("en_US-lessac-medium", VoiceProfilePreset.ENGLISH_FEMALE.piperModelId)
        assertEquals("en_US-ryan-medium", VoiceProfilePreset.ENGLISH_MALE.piperModelId)

        assertEquals("Priyamvada (प्रियम्वदा)", VoiceProfilePreset.HINDI_FEMALE.personaName)
        assertEquals("Rohan (रोहन)", VoiceProfilePreset.HINDI_MALE.personaName)
        assertTrue(VoiceProfilePreset.ENGLISH_FEMALE.personaName.contains("Lessac"))
        assertTrue(VoiceProfilePreset.ENGLISH_MALE.personaName.contains("Ryan"))

        // Verify exact Smart Boss 2.0 Hindi phrases
        assertEquals("चार्जर लग गया है बॉस। अब बैटरी चार्ज हो रही है।", DefaultBossMessages.HI_CHARGER_CONNECTED)
        assertEquals("बॉस, बैटरी 20 प्रतिशत हो गई है।", DefaultBossMessages.HI_BATTERY_20)
        assertEquals("बॉस, अब बैटरी आधी चार्ज हो चुकी है।", DefaultBossMessages.HI_BATTERY_50)
        assertEquals("बॉस, बैटरी 80 प्रतिशत तक पहुँच गई है।", DefaultBossMessages.HI_BATTERY_80)
        assertEquals("बॉस, बैटरी फुल हो गई है। अब चार्जर निकाल सकते हैं।", DefaultBossMessages.HI_BATTERY_FULL)
        assertEquals("बॉस, मोबाइल थोड़ा ज्यादा गर्म हो रहा है।", DefaultBossMessages.HI_HIGH_TEMP)
        assertEquals("बॉस, चार्जर निकाल दिया गया है।", DefaultBossMessages.HI_CHARGER_DISCONNECTED)
    }

    @Test
    fun `independent voice volume controller defaults to 100 percent 1_0f, preserves user volume, and never applies gain twice`() {
        // 1 & 2. Fresh installation defaults in UserSettings, AudioDiagnosticsState, and IndependentVoiceController must be 1.0f (100%)
        val defaultSettings = UserSettings()
        assertEquals(1.0f, defaultSettings.voiceVolume, 0.001f)
        assertEquals(1.0f, defaultSettings.lastRememberedVoiceVolume, 0.001f)
        assertTrue(defaultSettings.independentVolumeEnabled)

        val defaultDiagnostics = com.example.model.AudioDiagnosticsState()
        assertEquals(100, defaultDiagnostics.independentVolumePercent)
        assertEquals(1.0f, defaultDiagnostics.effectiveGainValue, 0.001f)

        val controller = IndependentVoiceController()
        // 4 & 5. Initial Test Voice and charging announcements both play at 100% (1.0f) gain
        assertEquals(1.0f, controller.getEffectiveGain(isPreviewOrTest = true), 0.001f)
        assertEquals(1.0f, controller.getEffectiveGain(isPreviewOrTest = false), 0.001f)

        // 3. Verify presets match Very Low (20%), Low (40%), Medium (60%), High (80%), Maximum (100% / 1.0f)
        assertEquals(0.20f, VoiceVolumePreset.VERY_LOW.volumeFraction, 0.001f)
        assertEquals(0.40f, VoiceVolumePreset.LOW.volumeFraction, 0.001f)
        assertEquals(0.60f, VoiceVolumePreset.MEDIUM.volumeFraction, 0.001f)
        assertEquals(0.80f, VoiceVolumePreset.HIGH.volumeFraction, 0.001f)
        assertEquals(1.00f, VoiceVolumePreset.MAXIMUM.volumeFraction, 0.001f)
        assertEquals("100%", VoiceVolumePreset.MAXIMUM.percentLabel)

        // Test 0% silent PCM scaling
        controller.updateConfiguration(volume = 0.0f, independentEnabled = true, applyToAll = true)
        val pcmSilence = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(20000.toShort())
            .putShort((-20000).toShort())
            .array()
        controller.applyPcm16VolumeInPlace(pcmSilence, pcmSilence.size, controller.getEffectiveGain(false))
        val silenceRead = ByteBuffer.wrap(pcmSilence).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0, silenceRead.short.toInt())
        assertEquals(0, silenceRead.short.toInt())

        // 5, 7 & 8. Verify user-selected 50% (0.50f) volume is preserved for charging announcements
        // AND verify PCM scaling + AudioTrack stream gain do NOT apply gain twice:
        controller.updateConfiguration(volume = 0.50f, independentEnabled = true, applyToAll = true)
        val effectiveAnnouncementGain = controller.getEffectiveGain(isPreviewOrTest = false)
        val audioTrackHardwareGainPcm16 = controller.getAudioTrackStreamGain(isPcm16SoftwareScaled = true, isPreviewOrTest = false)
        assertEquals(0.50f, effectiveAnnouncementGain, 0.001f)
        assertEquals(1.0f, audioTrackHardwareGainPcm16, 0.001f)
        // Combined signal gain = software PCM gain (0.50f) * AudioTrack unity gain (1.0f) = 0.50f (never 0.25f)
        assertEquals(0.50f, effectiveAnnouncementGain * audioTrackHardwareGainPcm16, 0.001f)

        val pcmHalf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(20000.toShort())
            .putShort((-16000).toShort())
            .array()
        controller.applyPcm16VolumeInPlace(pcmHalf, pcmHalf.size, effectiveAnnouncementGain)
        val halfRead = ByteBuffer.wrap(pcmHalf).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(10000, (halfRead.short.toInt() * audioTrackHardwareGainPcm16).toInt())
        assertEquals(-8000, (halfRead.short.toInt() * audioTrackHardwareGainPcm16).toInt())

        // Test 100% max volume clamping (no clipping)
        controller.updateConfiguration(volume = 1.5f, independentEnabled = true, applyToAll = true)
        assertEquals(1.0f, controller.getEffectiveGain(false), 0.001f)

        // 2 & 7. Verify SettingsDataStore default is 1.0f (100%) on fresh installation and preserves user-selected volume
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dataStore = com.example.data.preferences.SettingsDataStore(context)
        kotlinx.coroutines.runBlocking {
            val initialSnapshot = dataStore.getSettingsSnapshot()
            assertEquals(1.0f, initialSnapshot.voiceVolume, 0.001f)
            assertEquals(1.0f, initialSnapshot.lastRememberedVoiceVolume, 0.001f)

            // User changes volume to 60% (0.60f) -> verify DataStore preserves 0.60f
            dataStore.updateIndependentVolumeSettings(volume = 0.60f, rememberLastVolume = true)
            val updatedSnapshot = dataStore.getSettingsSnapshot()
            assertEquals(0.60f, updatedSnapshot.voiceVolume, 0.001f)
            assertEquals(0.60f, updatedSnapshot.lastRememberedVoiceVolume, 0.001f)

            // Restore 1.0f for clean state
            dataStore.updateIndependentVolumeSettings(volume = 1.0f, rememberLastVolume = true)
        }
    }

    @Test
    fun `voice priority queue orders Priority 1 Critical ahead of Priority 2 Important and Priority 3 Normal`() {
        val pq = PriorityBlockingQueue<QueuedVoiceAnnouncement>()
        val normalItem = QueuedVoiceAnnouncement(
            eventType = "Milestone 50%",
            rawMessage = "50%",
            clauses = listOf("50%"),
            priority = AnnouncementPriority.NORMAL,
            preset = VoiceProfilePreset.HINDI_FEMALE,
            speed = 1.0f,
            pitch = 1.0f
        )
        val importantItem = QueuedVoiceAnnouncement(
            eventType = "Charger Connected",
            rawMessage = "Connected",
            clauses = listOf("Connected"),
            priority = AnnouncementPriority.IMPORTANT,
            preset = VoiceProfilePreset.HINDI_FEMALE,
            speed = 1.0f,
            pitch = 1.0f
        )
        val criticalItem = QueuedVoiceAnnouncement(
            eventType = "Thermal Alert",
            rawMessage = "Overheating",
            clauses = listOf("Overheating"),
            priority = AnnouncementPriority.CRITICAL,
            preset = VoiceProfilePreset.HINDI_FEMALE,
            speed = 1.0f,
            pitch = 1.0f
        )

        pq.offer(normalItem)
        pq.offer(importantItem)
        pq.offer(criticalItem)

        assertEquals(AnnouncementPriority.CRITICAL, pq.poll()?.priority)
        assertEquals(AnnouncementPriority.IMPORTANT, pq.poll()?.priority)
        assertEquals(AnnouncementPriority.NORMAL, pq.poll()?.priority)
    }

    @Test
    fun `prosody and phonetics engine segments clauses and expands Hindi and English symbols naturally`() {
        val hindiClauses = ProsodyAndPhoneticsEngine.prepareConversationalClauses(
            rawText = "चार्जर लग गया है बॉस। अब बैटरी 50% चार्ज हो रही है।",
            language = VoiceLanguage.HINDI,
            naturalProsodyEnabled = true
        )
        assertEquals(2, hindiClauses.size)
        assertEquals("चार्जर लग गया है बॉस।", hindiClauses[0])
        assertTrue(hindiClauses[1].contains("50 प्रतिशत"))

        val englishClauses = ProsodyAndPhoneticsEngine.prepareConversationalClauses(
            rawText = "Charger connected, Boss. Temperature is 39°C at 1800 mA.",
            language = VoiceLanguage.ENGLISH,
            naturalProsodyEnabled = true
        )
        assertEquals(2, englishClauses.size)
        assertTrue(englishClauses[1].contains("degrees Celsius"))
        assertTrue(englishClauses[1].contains("milliamps"))
    }

    @Test
    fun `deep battery diagnostics parses cycle count, voltage, temp and keeps missing sensors null with explanation`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val reader = BatteryHardwareReader(context)

        val batteryIntent = Intent(Intent.ACTION_BATTERY_CHANGED).apply {
            putExtra(BatteryManager.EXTRA_LEVEL, 80)
            putExtra(BatteryManager.EXTRA_SCALE, 100)
            putExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_CHARGING)
            putExtra(BatteryManager.EXTRA_PLUGGED, BatteryManager.BATTERY_PLUGGED_AC)
            putExtra(BatteryManager.EXTRA_VOLTAGE, 4320) // 4.320 V
            putExtra(BatteryManager.EXTRA_TEMPERATURE, 368) // 36.8 °C
            putExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_GOOD)
            putExtra(BatteryManager.EXTRA_TECHNOLOGY, "Li-ion")
            putExtra("android.os.extra.CYCLE_COUNT", 142)
        }

        val snapshot = reader.readSnapshot(batteryIntent)
        assertEquals(80, snapshot.percentage)
        assertTrue(snapshot.isCharging)
        assertEquals(ChargingSource.AC, snapshot.chargingSource)
        assertEquals(4.32f, snapshot.voltageVolts ?: 0f, 0.01f)
        assertEquals(36.8f, snapshot.temperatureCelsius ?: 0f, 0.05f)
        assertEquals(142, snapshot.cycleCount)
        assertEquals("Good", snapshot.healthLabel)

        // Verify Android GOOD status is NEVER claimed as 100% battery health
        assertFalse(snapshot.healthReport.osHealthExplanation.contains("100% battery health"))
        assertTrue(snapshot.healthReport.osHealthExplanation.contains("does NOT mean 100% capacity retention"))

        // Missing current sensor in JVM test environment stays null and charging speed reports UNAVAILABLE
        assertNull(snapshot.currentMilliAmps)
        assertEquals(ChargingSpeedTier.UNAVAILABLE, snapshot.chargingSpeedTier)

        // Verify diagnostic metrics list has source & confidence metadata
        assertTrue(snapshot.diagnosticMetrics.isNotEmpty())
        val currentMetric = snapshot.diagnosticMetrics.first { it.title == "Instant Battery Current" }
        assertEquals("N/A", currentMetric.value)
        assertEquals(MetricConfidence.UNAVAILABLE, currentMetric.confidence)
    }

    @Test
    fun `compose UI navigates across tabs and displays Independent Voice Volume Control and Audio Diagnostics`() {
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("dashboard_screen").assertIsDisplayed()

        // Navigate to Voice Studio tab and verify Independent Voice Volume Card is displayed
        composeTestRule.onNodeWithTag("nav_tab_voice_alerts").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("voice_settings_screen").assertIsDisplayed()
        composeTestRule.onNodeWithTag("independent_voice_volume_card").assertIsDisplayed()
        composeTestRule.onNodeWithTag("switch_independent_volume").assertIsDisplayed()
        composeTestRule.onNodeWithTag("slider_independent_volume").assertIsDisplayed()

        // Navigate to Dedicated Battery Health Analysis tab
        composeTestRule.onNodeWithTag("nav_tab_battery_health").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("battery_health_screen").assertIsDisplayed()

        // Navigate to Hardware & OEM tab
        composeTestRule.onNodeWithTag("nav_tab_hardware_oem").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("hardware_oem_screen").assertIsDisplayed()

        // Navigate to Analytics & History tab
        composeTestRule.onNodeWithTag("nav_tab_history_about").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("history_about_screen").assertIsDisplayed()
        composeTestRule.onNodeWithTag("live_charging_analytics_card").assertIsDisplayed()
    }
}

