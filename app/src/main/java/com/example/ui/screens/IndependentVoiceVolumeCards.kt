package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VolumeDown
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.model.AnnouncementRepeatPolicy
import com.example.model.AudioDiagnosticsState
import com.example.model.MediaDuckingLevel
import com.example.model.UserSettings
import com.example.model.VoiceProfilePreset
import com.example.model.VoiceVolumePreset
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.EmeraldCharge
import com.example.ui.theme.NavyCardBorder
import com.example.ui.theme.NavySurface
import com.example.ui.theme.NavySurfaceElevated
import com.example.ui.theme.PurpleVoiceAccent
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.ThermalCrimson
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Section 2, 4, 7 & 8: Independent Voice Volume Control Card
 *
 * Implements:
 * 1. Enable Independent Voice Volume – ON/OFF
 * 2. Battery Boss Voice Volume – 0% to 100% (0.0 to 1.0, default 1.00 / 100%)
 * 3. Test Voice button
 * 4. Voice Preview button
 * 5. Voice Volume Presets (Very Low, Low, Medium, High, Maximum)
 * 6. Remember Last Voice Volume toggle
 * 7. Apply Settings to All Announcements toggle
 * 8. Media Ducking (OFF, LOW, MEDIUM, HIGH) & Priority Queue Repeat Policy
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun IndependentVoiceVolumeCard(
    settings: UserSettings,
    isSpeaking: Boolean,
    onLiveVolumeChange: (Float) -> Unit,
    onUpdateIndependentVolumeSettings: (
        independentEnabled: Boolean?,
        volume: Float?,
        rememberLastVolume: Boolean?,
        applyToAllAnnouncements: Boolean?,
        mediaDuckingLevel: MediaDuckingLevel?,
        repeatPolicy: AnnouncementRepeatPolicy?
    ) -> Unit,
    onTestVoice: () -> Unit,
    onPreviewActiveVoice: (VoiceProfilePreset) -> Unit,
    onTestPriorityBurst: () -> Unit,
    onStopSpeaking: () -> Unit,
    modifier: Modifier = Modifier
) {
    var sliderVolume by remember(settings.voiceVolume) {
        mutableFloatStateOf(settings.voiceVolume.coerceIn(0.0f, 1.0f))
    }
    val percentDisplay = (sliderVolume * 100).roundToInt()

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("independent_voice_volume_card"),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = NavySurface),
        border = BorderStroke(
            width = 1.5.dp,
            color = if (settings.independentVolumeEnabled) EmeraldCharge else AmberWarning.copy(alpha = 0.7f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = when {
                            percentDisplay == 0 -> Icons.Default.VolumeOff
                            percentDisplay < 50 -> Icons.Default.VolumeDown
                            else -> Icons.Default.VolumeUp
                        },
                        contentDescription = "Voice Volume Control",
                        tint = if (settings.independentVolumeEnabled) EmeraldCharge else AmberWarning,
                        modifier = Modifier.size(26.dp)
                    )
                    Column {
                        Text(
                            text = "VOICE VOLUME CONTROL",
                            style = MaterialTheme.typography.titleMedium,
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Dedicated PCM Gain Pipeline • Independent of System Sliders",
                            style = MaterialTheme.typography.bodySmall,
                            color = ElectricCyan
                        )
                    }
                }

                Surface(
                    color = if (settings.independentVolumeEnabled) {
                        EmeraldCharge.copy(alpha = 0.18f)
                    } else {
                        AmberWarning.copy(alpha = 0.18f)
                    },
                    shape = RoundedCornerShape(50),
                    border = BorderStroke(
                        1.dp,
                        if (settings.independentVolumeEnabled) EmeraldCharge else AmberWarning
                    )
                ) {
                    Text(
                        text = if (settings.independentVolumeEnabled) "INDEPENDENT $percentDisplay%" else "FALLBACK $percentDisplay%",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (settings.independentVolumeEnabled) EmeraldCharge else AmberWarning,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }

            // 1. Enable Independent Voice Volume – ON/OFF
            VolumeSwitchRow(
                title = "Enable Independent Voice Volume",
                subtitle = "Synthesizes speech to 16-bit PCM WAV and plays via dedicated Accessibility AudioTrack so Media, Alarm, Ringtone & Notification sliders do not alter Battery Boss volume",
                checked = settings.independentVolumeEnabled,
                onCheckedChange = { enabled ->
                    onUpdateIndependentVolumeSettings(enabled, null, null, null, null, null)
                },
                testTag = "switch_independent_volume"
            )

            // Mode Status & Fallback Explanation Banner
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = NavySurfaceElevated,
                border = BorderStroke(
                    1.dp,
                    if (settings.independentVolumeEnabled) ElectricCyan.copy(alpha = 0.45f) else AmberWarning.copy(alpha = 0.6f)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        imageVector = if (settings.independentVolumeEnabled) Icons.Default.CheckCircle else Icons.Default.Info,
                        contentDescription = null,
                        tint = if (settings.independentVolumeEnabled) ElectricCyan else AmberWarning,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = if (settings.independentVolumeEnabled) {
                            "Independent PCM Mode Active (Direct TTS Disabled): Speech is synthesized to WAV via synthesizeToFile(), decoded to 16-bit PCM, scaled in a single stage (0.00–1.00, default 100%), and played via AudioTrack (USAGE_ASSISTANCE_ACCESSIBILITY, AudioTrack gain locked at 1.0f). Note: On Android devices without an active AccessibilityService, the OS AudioPolicyManager routes USAGE_ASSISTANCE_ACCESSIBILITY through the hardware STREAM_MUSIC (Media Volume) master attenuator."
                        } else {
                            "Fallback Mode Active (Independent Mode Disabled): Battery Boss routes audio via standard USAGE_MEDIA stream with relative Bundle gain. Limitation: Spoken loudness scales with the device's system Media volume slider and is muted if Media volume is at 0%."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            }

            HorizontalDivider(color = NavyCardBorder)

            // 2. Battery Boss Voice Volume – 0% to 100% Slider
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Battery Boss Voice Volume (0% – 100%)",
                        style = MaterialTheme.typography.titleSmall,
                        color = TextPrimary,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "$percentDisplay% (${String.format(Locale.US, "%.2f", sliderVolume)} Gain)",
                        style = MaterialTheme.typography.labelLarge,
                        color = EmeraldCharge,
                        fontWeight = FontWeight.Bold
                    )
                }

                Slider(
                    value = sliderVolume,
                    onValueChange = { newVal ->
                        sliderVolume = newVal.coerceIn(0.0f, 1.0f)
                        onLiveVolumeChange(sliderVolume)
                    },
                    onValueChangeFinished = {
                        onUpdateIndependentVolumeSettings(null, sliderVolume, null, null, null, null)
                    },
                    valueRange = 0.0f..1.0f,
                    colors = SliderDefaults.colors(
                        thumbColor = EmeraldCharge,
                        activeTrackColor = EmeraldCharge,
                        inactiveTrackColor = NavySurfaceElevated
                    ),
                    modifier = Modifier.testTag("slider_independent_volume")
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("0% (Silent)", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                    Text("Default: 100% (1.0f)", style = MaterialTheme.typography.labelSmall, color = ElectricCyan)
                    Text("100% (Max)", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                }
            }

            // 3 & 4. Test Voice button & Voice Preview button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onTestVoice,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("btn_volume_test_voice"),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = EmeraldCharge,
                        contentColor = Color(0xFF00210E)
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.VolumeUp,
                        contentDescription = "Test Voice",
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Test Voice ($percentDisplay%)",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold
                    )
                }

                OutlinedButton(
                    onClick = { onPreviewActiveVoice(settings.activePreset) },
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("btn_volume_preview_voice"),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, ElectricCyan),
                    contentPadding = PaddingValues(horizontal = 12.dp)
                ) {
                    Icon(
                        imageVector = if (isSpeaking) Icons.Default.GraphicEq else Icons.Default.PlayArrow,
                        contentDescription = "Voice Preview",
                        tint = ElectricCyan,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Voice Preview",
                        style = MaterialTheme.typography.labelLarge,
                        color = ElectricCyan,
                        fontWeight = FontWeight.Bold
                    )
                }

                if (isSpeaking) {
                    OutlinedButton(
                        onClick = onStopSpeaking,
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("btn_volume_stop_voice"),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, ThermalCrimson)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Stop,
                            contentDescription = "Stop Voice",
                            tint = ThermalCrimson
                        )
                    }
                }
            }

            // 5. Voice Volume Presets: Very Low, Low, Medium, High, Maximum
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "VOICE VOLUME PRESETS",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                    fontWeight = FontWeight.Bold
                )
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    VoiceVolumePreset.entries.forEach { preset ->
                        val isSelected = abs(sliderVolume - preset.volumeFraction) < 0.03f
                        FilterChip(
                            selected = isSelected,
                            onClick = {
                                sliderVolume = preset.volumeFraction
                                onUpdateIndependentVolumeSettings(
                                    null,
                                    preset.volumeFraction,
                                    null,
                                    null,
                                    null,
                                    null
                                )
                            },
                            label = {
                                Text("${preset.label} (${preset.percentLabel})")
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = EmeraldCharge.copy(alpha = 0.22f),
                                selectedLabelColor = EmeraldCharge
                            ),
                            modifier = Modifier.testTag("preset_volume_${preset.name.lowercase(Locale.US)}")
                        )
                    }
                }
            }

            HorizontalDivider(color = NavyCardBorder)

            // 6. Remember Last Voice Volume
            VolumeSwitchRow(
                title = "Remember Last Voice Volume",
                subtitle = "Persists your exact Battery Boss voice volume (${(settings.lastRememberedVoiceVolume * 100).roundToInt()}%) across app restarts and device reboots",
                checked = settings.rememberLastVoiceVolume,
                onCheckedChange = { remember ->
                    onUpdateIndependentVolumeSettings(null, null, remember, null, null, null)
                },
                testTag = "switch_remember_last_volume"
            )

            // 7. Apply Settings to All Announcements
            VolumeSwitchRow(
                title = "Apply Settings to All Announcements",
                subtitle = "Enforces this independent volume level across charger connect/disconnect, 20%/50%/80%/100% milestones, thermal warnings, and background service alerts",
                checked = settings.applyVolumeToAllAnnouncements,
                onCheckedChange = { applyAll ->
                    onUpdateIndependentVolumeSettings(null, null, null, applyAll, null, null)
                },
                testTag = "switch_apply_to_all_announcements"
            )

            HorizontalDivider(color = NavyCardBorder)

            // Smart Audio Focus & Media Ducking (OFF, LOW, MEDIUM, HIGH)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = "Media Ducking",
                        tint = ElectricCyan,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "SMART AUDIO FOCUS & MEDIA DUCKING",
                        style = MaterialTheme.typography.labelLarge,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    text = settings.mediaDuckingLevel.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    MediaDuckingLevel.entries.forEach { level ->
                        val selected = settings.mediaDuckingLevel == level
                        FilterChip(
                            selected = selected,
                            onClick = {
                                onUpdateIndependentVolumeSettings(null, null, null, null, level, null)
                            },
                            label = { Text("Ducking: ${level.label}") },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = ElectricCyan.copy(alpha = 0.22f),
                                selectedLabelColor = ElectricCyan
                            ),
                            modifier = Modifier.testTag("chip_ducking_${level.name.lowercase(Locale.US)}")
                        )
                    }
                }
            }

            // Priority Queue & Repeat Policy
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.QueueMusic,
                        contentDescription = "Priority Queue Policy",
                        tint = PurpleVoiceAccent,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "VOICE PRIORITY QUEUE & REPEAT POLICY",
                        style = MaterialTheme.typography.labelLarge,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    text = "P1 Critical (Overheating/Low Battery) > P2 Important (Connect/Disconnect/100%) > P3 Normal (20%/50%/80% Milestones). Announcements never overlap.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AnnouncementRepeatPolicy.entries.forEach { policy ->
                        val selected = settings.repeatPolicy == policy
                        FilterChip(
                            selected = selected,
                            onClick = {
                                onUpdateIndependentVolumeSettings(null, null, null, null, null, policy)
                            },
                            label = { Text(policy.label) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = PurpleVoiceAccent.copy(alpha = 0.22f),
                                selectedLabelColor = PurpleVoiceAccent
                            ),
                            modifier = Modifier.testTag("chip_repeat_${policy.name.lowercase(Locale.US)}")
                        )
                    }
                }
                OutlinedButton(
                    onClick = onTestPriorityBurst,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("btn_test_priority_queue"),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, PurpleVoiceAccent.copy(alpha = 0.7f))
                ) {
                    Icon(
                        imageVector = Icons.Default.QueueMusic,
                        contentDescription = null,
                        tint = PurpleVoiceAccent,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "TEST RAPID MULTI-EVENT QUEUE (NON-OVERLAPPING P2 + P3)",
                        style = MaterialTheme.typography.labelMedium,
                        color = PurpleVoiceAccent
                    )
                }
            }
        }
    }
}

/**
 * Section 10 & 11: Audio Diagnostics & Playback Trace Card + Honest Android OS Stream Routing Disclosure
 */
@Composable
fun AudioDiagnosticsCard(
    diagnostics: AudioDiagnosticsState,
    onRefreshDiagnostics: () -> Unit,
    onRunQuickVoiceTest: () -> Unit,
    onRunQueueBurstTest: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("audio_diagnostics_card"),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = NavySurface),
        border = BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.5f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.Speaker,
                        contentDescription = "Audio Diagnostics",
                        tint = ElectricCyan
                    )
                    Column {
                        Text(
                            text = "VOICE & AUDIO TRACE DIAGNOSTICS",
                            style = MaterialTheme.typography.titleMedium,
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Live Playback Pipeline Trace & OS Routing Inspection",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                }

                OutlinedButton(
                    onClick = onRefreshDiagnostics,
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    modifier = Modifier.testTag("btn_refresh_audio_diagnostics")
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Refresh Audio Diagnostics",
                        tint = ElectricCyan,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Refresh", style = MaterialTheme.typography.labelSmall, color = ElectricCyan)
                }
            }

            if (diagnostics.lastPlaybackError != "None") {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = ThermalCrimson.copy(alpha = 0.16f),
                    border = BorderStroke(1.5.dp, ThermalCrimson),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("independent_playback_error_banner")
                ) {
                    Text(
                        text = diagnostics.lastPlaybackError,
                        style = MaterialTheme.typography.bodyMedium,
                        color = ThermalCrimson,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            val synthStatus = when (diagnostics.synthesizeToFileSucceeded) {
                null -> "Awaiting First Utterance"
                true -> "Yes (WAV Synthesized)"
                false -> "FAILED (Direct TTS Disabled)"
            }
            val wavDecodeStatus = when (diagnostics.pcmWavDecoded) {
                null -> "Awaiting First Utterance"
                true -> "Yes (${diagnostics.decodedWavFormatSummary})"
                false -> "FAILED"
            }
            val trackCreatedStatus = when (diagnostics.audioTrackCreated) {
                null -> "Awaiting First Utterance"
                true -> "Yes (STATE_INITIALIZED)"
                false -> "FAILED"
            }
            val fallbackStatus = if (diagnostics.independentVolumeEnabled) {
                "false (Strictly Disabled in Independent Mode)"
            } else {
                diagnostics.directTtsFallbackUsed.toString()
            }

            val rows = listOf(
                "Current Selected Voice" to diagnostics.selectedVoiceLabel,
                "Selected TTS Engine" to diagnostics.selectedTtsEnginePackage,
                "Current Playback Engine" to diagnostics.playbackEngineLabel,
                "Last Traced Entry Point" to diagnostics.lastTraceEntryPoint,
                "synthesizeToFile() Succeeded" to synthStatus,
                "PCM WAV Decoded" to wavDecodeStatus,
                "AudioTrack Created" to trackCreatedStatus,
                "Actual AudioAttributes Usage" to diagnostics.actualAudioAttributesUsage,
                "Actual Content Type" to diagnostics.actualAudioAttributesContentType,
                "AudioTrack Playback State" to diagnostics.audioTrackPlaybackState,
                "Direct TTS.speak() Fallback Used" to fallbackStatus,
                "Effective PCM Gain (Single-Stage)" to "${diagnostics.independentVolumePercent}% (${String.format(Locale.US, "%.2f", diagnostics.effectiveGainValue)} Gain)",
                "AudioTrack Stream Gain" to "${String.format(Locale.US, "%.2f", diagnostics.audioTrackStreamGainValue)} (Unity = No Double Gain)",
                "System Media Volume (STREAM_MUSIC)" to (diagnostics.systemMediaVolumePercent?.let { "$it% (Read-Only OS Stream)" } ?: "N/A"),
                "OS AccessibilityService Active" to if (diagnostics.isAccessibilityServiceEnabledOnOs) "Yes (STREAM_ACCESSIBILITY Active)" else "No (OS Routes via STREAM_MUSIC)",
                "Playback State" to diagnostics.playbackState,
                "Audio Focus State" to diagnostics.audioFocusState,
                "Selected Audio Output Device" to diagnostics.outputDeviceName.ifBlank { "N/A" },
                "Current Announcement Priority" to diagnostics.currentPriorityLabel,
                "Queue Status" to diagnostics.queueSummary,
                "Call / Alarm Interruption State" to diagnostics.callOrAlarmState,
                "Last Playback Error" to diagnostics.lastPlaybackError
            )

            Surface(
                shape = RoundedCornerShape(14.dp),
                color = NavySurfaceElevated,
                border = BorderStroke(1.dp, NavyCardBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    rows.forEachIndexed { index, (label, value) ->
                        if (index > 0) {
                            HorizontalDivider(color = NavyCardBorder.copy(alpha = 0.5f))
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.Top
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary,
                                modifier = Modifier.weight(0.46f)
                            )
                            Text(
                                text = value,
                                style = MaterialTheme.typography.labelMedium,
                                color = when {
                                    value == "N/A" -> TextMuted
                                    label == "Last Playback Error" && value != "None" -> ThermalCrimson
                                    label.startsWith("Effective PCM Gain") -> EmeraldCharge
                                    label == "Direct TTS.speak() Fallback Used" -> ElectricCyan
                                    label == "OS AccessibilityService Active" && !diagnostics.isAccessibilityServiceEnabledOnOs -> AmberWarning
                                    else -> TextPrimary
                                },
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(0.54f)
                            )
                        }
                    }
                }
            }

            // Verification Actions
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onRunQuickVoiceTest,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("diag_btn_test_voice"),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, EmeraldCharge)
                ) {
                    Icon(
                        imageVector = Icons.Default.VolumeUp,
                        contentDescription = null,
                        tint = EmeraldCharge,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Test Independent Audio", style = MaterialTheme.typography.labelMedium, color = EmeraldCharge)
                }

                OutlinedButton(
                    onClick = onRunQueueBurstTest,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("diag_btn_test_queue"),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, ElectricCyan)
                ) {
                    Icon(
                        imageVector = Icons.Default.QueueMusic,
                        contentDescription = null,
                        tint = ElectricCyan,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Test Queue Burst", style = MaterialTheme.typography.labelMedium, color = ElectricCyan)
                }
            }

            // Honest Android OS & OEM Stream Routing Limitation Disclosure
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = NavySurfaceElevated,
                border = BorderStroke(1.dp, AmberWarning.copy(alpha = 0.6f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = "Android Audio Architecture Notes",
                            tint = AmberWarning,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "HONEST ANDROID OS & OEM STREAM ROUTING DISCLOSURE",
                            style = MaterialTheme.typography.labelSmall,
                            color = AmberWarning,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        text = diagnostics.osStreamRoutingLimitationNote,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextPrimary
                    )
                    Text(
                        text = "• Why Media Volume Still Affects Output Without AccessibilityService: In AOSP AudioPolicyManager (Engine.cpp), AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY (11) is mapped to a separate STREAM_ACCESSIBILITY volume curve ONLY when an Android AccessibilityService (e.g., TalkBack/Select-to-Speak) is enabled on the device (and supported by the OEM ROM). Otherwise, the Android OS kernel mixer routes USAGE_ASSISTANCE_ACCESSIBILITY through STREAM_MUSIC (Media Volume).\n" +
                            "• What Battery Boss Controls Independently: Battery Boss never calls TextToSpeech.speak() in Independent Mode and never modifies system volume sliders. It controls the 16-bit PCM digital gain (0%–100%) in a single stage with AudioTrack.setVolume(1.0f), multiplied prior to the Android OS hardware stream attenuator.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            }
        }
    }
}

@Composable
private fun VolumeSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    testTag: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color(0xFF00210E),
                checkedTrackColor = EmeraldCharge
            ),
            modifier = Modifier.testTag(testTag)
        )
    }
}
