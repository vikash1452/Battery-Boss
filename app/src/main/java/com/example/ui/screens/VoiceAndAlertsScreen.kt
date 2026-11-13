package com.example.ui.screens

import android.content.Intent
import android.provider.Settings
import android.speech.tts.TextToSpeech
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CompareArrows
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.NightlightRound
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.SettingsVoice
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.model.DefaultBossMessages
import com.example.model.OfflineVoiceModelPack
import com.example.model.UserSettings
import com.example.model.VoiceGender
import com.example.model.VoiceLanguage
import com.example.model.VoiceModelState
import com.example.model.VoiceProfilePreset
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
import kotlin.math.roundToInt

import com.example.model.AnnouncementRepeatPolicy
import com.example.model.AudioDiagnosticsState
import com.example.model.MediaDuckingLevel

@Composable
fun VoiceAndAlertsScreen(
    settings: UserSettings,
    voiceModelPacks: List<OfflineVoiceModelPack>,
    audioDiagnostics: AudioDiagnosticsState,
    isSpeaking: Boolean,
    previewingPreset: VoiceProfilePreset?,
    onSelectPreset: (VoiceProfilePreset) -> Unit,
    onPreviewPreset: (VoiceProfilePreset, String?) -> Unit,
    onStopSpeaking: () -> Unit,
    onUpdateLanguage: (VoiceLanguage) -> Unit,
    onUpdateGender: (VoiceGender) -> Unit,
    onUpdateVoiceParameters: (Float, Float, Float, Boolean?) -> Unit,
    onLiveVolumeChange: (Float) -> Unit,
    onUpdateIndependentVolumeSettings: (
        independentEnabled: Boolean?,
        volume: Float?,
        rememberLastVolume: Boolean?,
        applyToAllAnnouncements: Boolean?,
        mediaDuckingLevel: MediaDuckingLevel?,
        repeatPolicy: AnnouncementRepeatPolicy?
    ) -> Unit,
    onTestVoice: (String?) -> Unit,
    onTestPriorityBurst: () -> Unit,
    onRefreshAudioDiagnostics: () -> Unit,
    onInstallOrVerifyModel: (String) -> Unit,
    onUpdateChargingAlerts: (
        intervalPercent: Int?,
        intervalEnabled: Boolean?,
        smartMilestones: Boolean?,
        fullCharge: Boolean?,
        chargerConnected: Boolean?,
        chargerDisconnected: Boolean?,
        lowBattery: Boolean?,
        lowBatteryThreshold: Int?,
        tempWarning: Boolean?,
        tempWarningCelsius: Int?
    ) -> Unit,
    onUpdateQuietHoursAndRinger: (
        quietHoursEnabled: Boolean?,
        quietStartHour: Int?,
        quietEndHour: Int?,
        respectSystemRinger: Boolean?
    ) -> Unit,
    onSaveCustomMessages: (Boolean, String, String, String, String, String, String, String, String, String) -> Unit,
    onResetCustomMessages: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("voice_settings_screen"),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 0. Independent Voice Volume Control System (0% to 100%, Presets, Ducking, Queue Policy)
        item {
            IndependentVoiceVolumeCard(
                settings = settings,
                isSpeaking = isSpeaking,
                onLiveVolumeChange = onLiveVolumeChange,
                onUpdateIndependentVolumeSettings = onUpdateIndependentVolumeSettings,
                onTestVoice = { onTestVoice(null) },
                onPreviewActiveVoice = { preset -> onPreviewPreset(preset, null) },
                onTestPriorityBurst = onTestPriorityBurst,
                onStopSpeaking = onStopSpeaking
            )
        }

        // 1. Voice Studio – Listen to All 4 Voices Before Selecting One
        item {
            VoiceStudioGalleryCard(
                activePreset = settings.activePreset,
                previewingPreset = previewingPreset,
                isSpeaking = isSpeaking,
                onSelectPreset = onSelectPreset,
                onPreviewPreset = { preset -> onPreviewPreset(preset, null) },
                onStopSpeaking = onStopSpeaking
            )
        }

        // 2. Audio Sample Comparison Matrix (Side-by-Side 4-Voice Comparison)
        item {
            AudioSampleComparisonCard(
                isSpeaking = isSpeaking,
                previewingPreset = previewingPreset,
                onPreviewScenarioWithVoice = { preset, phrase ->
                    onPreviewPreset(preset, phrase)
                },
                onStopSpeaking = onStopSpeaking
            )
        }

        // 3. Expressive Prosody, Speech Rate, Pitch & Volume Tuning
        item {
            VoiceTuningSectionCard(
                settings = settings,
                isSpeaking = isSpeaking,
                onUpdateLanguage = onUpdateLanguage,
                onUpdateGender = onUpdateGender,
                onUpdateVoiceParameters = onUpdateVoiceParameters,
                onTestVoice = onTestVoice,
                onStopSpeaking = onStopSpeaking
            )
        }

        // 4. Live Voice & Audio Diagnostics Card
        item {
            AudioDiagnosticsCard(
                diagnostics = audioDiagnostics,
                onRefreshDiagnostics = onRefreshAudioDiagnostics,
                onRunQuickVoiceTest = { onTestVoice(null) },
                onRunQueueBurstTest = onTestPriorityBurst
            )
        }

        // 5. Offline Neural Voice Model Manager (Piper VITS / Sherpa-ONNX + System Packs)
        item {
            OfflineVoiceModelManagerCard(
                voiceModelPacks = voiceModelPacks,
                selectedModelId = settings.selectedModelId,
                onInstallOrVerifyModel = onInstallOrVerifyModel,
                onOpenSystemTtsInstaller = {
                    runCatching {
                        val installIntent = Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        context.startActivity(installIntent)
                    }.onFailure {
                        runCatching {
                            val ttsSettingsIntent = Intent("com.android.settings.TTS_SETTINGS").apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                            context.startActivity(ttsSettingsIntent)
                        }.onFailure {
                            context.startActivity(
                                Intent(Settings.ACTION_SETTINGS).apply {
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                }
                            )
                        }
                    }
                }
            )
        }

        // 6. Charging Intervals & Smart Milestone Alert Triggers
        item {
            ChargingAnnouncementRulesCard(
                settings = settings,
                onUpdateChargingAlerts = onUpdateChargingAlerts
            )
        }

        // 7. Quiet Hours & System Ringer Mode Controls
        item {
            QuietHoursAndRingerCard(
                settings = settings,
                onUpdateQuietHoursAndRinger = onUpdateQuietHoursAndRinger
            )
        }

        // 8. Conversational Smart Boss Custom Message Editor (All 9 Events)
        item {
            CustomMessagesEditorCard(
                settings = settings,
                onSaveCustomMessages = onSaveCustomMessages,
                onResetCustomMessages = onResetCustomMessages,
                onPreviewPhrase = { phrase -> onTestVoice(phrase) }
            )
        }
    }
}

@Composable
private fun VoiceStudioGalleryCard(
    activePreset: VoiceProfilePreset,
    previewingPreset: VoiceProfilePreset?,
    isSpeaking: Boolean,
    onSelectPreset: (VoiceProfilePreset) -> Unit,
    onPreviewPreset: (VoiceProfilePreset) -> Unit,
    onStopSpeaking: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("voice_studio_card"),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = NavySurface),
        border = BorderStroke(1.dp, EmeraldCharge.copy(alpha = 0.5f))
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
                        imageVector = Icons.Default.RecordVoiceOver,
                        contentDescription = "Voice Studio",
                        tint = EmeraldCharge
                    )
                    Column {
                        Text(
                            text = "PRO VOICE STUDIO (4 NEURAL PROFILES)",
                            style = MaterialTheme.typography.titleMedium,
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Audition all 4 offline Hindi & Indian English voices before selecting",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                }
                if (isSpeaking) {
                    OutlinedButton(
                        onClick = onStopSpeaking,
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, ThermalCrimson),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Stop,
                            contentDescription = "Stop Voice",
                            tint = ThermalCrimson,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Stop", style = MaterialTheme.typography.labelMedium, color = ThermalCrimson)
                    }
                }
            }

            VoiceProfilePreset.entries.forEach { preset ->
                val isSelected = activePreset == preset
                val isPreviewingThis = isSpeaking && previewingPreset == preset

                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = if (isSelected) EmeraldCharge.copy(alpha = 0.14f) else NavySurfaceElevated,
                    border = BorderStroke(
                        width = if (isSelected || isPreviewingThis) 1.5.dp else 1.dp,
                        color = when {
                            isPreviewingThis -> ElectricCyan
                            isSelected -> EmeraldCharge
                            else -> NavyCardBorder
                        }
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "${preset.title} • ${preset.personaName}",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = if (isSelected) EmeraldCharge else TextPrimary,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = preset.voiceDescription,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondary
                                )
                            }
                            if (isSelected) {
                                Surface(
                                    color = EmeraldCharge,
                                    shape = RoundedCornerShape(50)
                                ) {
                                    Text(
                                        text = "ACTIVE",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color(0xFF00210E),
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
                                    )
                                }
                            }
                        }

                        Text(
                            text = "\"${preset.samplePhrase}\"",
                            style = MaterialTheme.typography.bodySmall,
                            color = ElectricCyan
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedButton(
                                onClick = { onPreviewPreset(preset) },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("studio_preview_${preset.id}"),
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.7f))
                            ) {
                                Icon(
                                    imageVector = if (isPreviewingThis) Icons.Default.GraphicEq else Icons.Default.PlayArrow,
                                    contentDescription = "Preview ${preset.title}",
                                    tint = ElectricCyan,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (isPreviewingThis) "Playing Sample..." else "Preview Voice",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = ElectricCyan
                                )
                            }

                            Button(
                                onClick = { onSelectPreset(preset) },
                                enabled = !isSelected,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("studio_select_${preset.id}"),
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = EmeraldCharge,
                                    contentColor = Color(0xFF00210E),
                                    disabledContainerColor = NavySurface,
                                    disabledContentColor = EmeraldCharge
                                )
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (isSelected) "Selected Voice" else "Select Voice",
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AudioSampleComparisonCard(
    isSpeaking: Boolean,
    previewingPreset: VoiceProfilePreset?,
    onPreviewScenarioWithVoice: (VoiceProfilePreset, String) -> Unit,
    onStopSpeaking: () -> Unit
) {
    val scenarios = DefaultBossMessages.comparisonScenarios
    var selectedScenario by remember { mutableStateOf(scenarios.first()) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("audio_sample_comparison_card"),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = NavySurface),
        border = BorderStroke(1.dp, NavyCardBorder)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.CompareArrows,
                    contentDescription = "Audio Sample Comparison",
                    tint = ElectricCyan
                )
                Column {
                    Text(
                        text = "AUDIO SAMPLE COMPARISON LAB",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Pick an event scenario and compare all 4 voices side-by-side",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            }

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                scenarios.forEach { scenario ->
                    val isSelected = selectedScenario.id == scenario.id
                    FilterChip(
                        selected = isSelected,
                        onClick = { selectedScenario = scenario },
                        label = { Text("${scenario.badge} • ${scenario.title}") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = ElectricCyan.copy(alpha = 0.2f),
                            selectedLabelColor = ElectricCyan
                        ),
                        modifier = Modifier.testTag("compare_scenario_${scenario.id}")
                    )
                }
            }

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
                    Text(
                        text = "HINDI SCRIPT (${selectedScenario.title.uppercase(Locale.US)})",
                        style = MaterialTheme.typography.labelSmall,
                        color = EmeraldCharge
                    )
                    Text(
                        text = selectedScenario.hindiText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextPrimary,
                        fontWeight = FontWeight.Medium
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                onPreviewScenarioWithVoice(
                                    VoiceProfilePreset.HINDI_FEMALE,
                                    selectedScenario.hindiText
                                )
                            },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("compare_play_hindi_female"),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.VolumeUp,
                                contentDescription = null,
                                tint = EmeraldCharge,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Hindi Female", style = MaterialTheme.typography.labelMedium)
                        }
                        OutlinedButton(
                            onClick = {
                                onPreviewScenarioWithVoice(
                                    VoiceProfilePreset.HINDI_MALE,
                                    selectedScenario.hindiText
                                )
                            },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("compare_play_hindi_male"),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.VolumeUp,
                                contentDescription = null,
                                tint = ElectricCyan,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Hindi Male", style = MaterialTheme.typography.labelMedium)
                        }
                    }

                    HorizontalDivider(color = NavyCardBorder)

                    Text(
                        text = "INDIAN ENGLISH SCRIPT (${selectedScenario.title.uppercase(Locale.US)})",
                        style = MaterialTheme.typography.labelSmall,
                        color = ElectricCyan
                    )
                    Text(
                        text = selectedScenario.englishText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextPrimary,
                        fontWeight = FontWeight.Medium
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                onPreviewScenarioWithVoice(
                                    VoiceProfilePreset.ENGLISH_FEMALE,
                                    selectedScenario.englishText
                                )
                            },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("compare_play_english_female"),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.VolumeUp,
                                contentDescription = null,
                                tint = EmeraldCharge,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("English Female", style = MaterialTheme.typography.labelMedium)
                        }
                        OutlinedButton(
                            onClick = {
                                onPreviewScenarioWithVoice(
                                    VoiceProfilePreset.ENGLISH_MALE,
                                    selectedScenario.englishText
                                )
                            },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("compare_play_english_male"),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.VolumeUp,
                                contentDescription = null,
                                tint = ElectricCyan,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("English Male", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VoiceTuningSectionCard(
    settings: UserSettings,
    isSpeaking: Boolean,
    onUpdateLanguage: (VoiceLanguage) -> Unit,
    onUpdateGender: (VoiceGender) -> Unit,
    onUpdateVoiceParameters: (Float, Float, Float, Boolean?) -> Unit,
    onTestVoice: (String?) -> Unit,
    onStopSpeaking: () -> Unit
) {
    var speed by remember(settings.voiceSpeed) { mutableFloatStateOf(settings.voiceSpeed) }
    var pitch by remember(settings.voicePitch) { mutableFloatStateOf(settings.voicePitch) }
    var volume by remember(settings.voiceVolume) { mutableFloatStateOf(settings.voiceVolume) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = NavySurface),
        border = BorderStroke(1.dp, NavyCardBorder)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.RecordVoiceOver,
                    contentDescription = "Prosody & Acoustic Tuning",
                    tint = EmeraldCharge
                )
                Column {
                    Text(
                        text = "EXPRESSIVE PROSODY & ACOUSTIC CONTROLS",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Active Profile: ${settings.activePreset.title} (${settings.activePreset.personaName})",
                        style = MaterialTheme.typography.bodySmall,
                        color = ElectricCyan
                    )
                }
            }

            SettingSwitchRow(
                title = "Natural Prosody & Breath Pauses",
                subtitle = "Segments multi-sentence announcements with natural 210ms breath pauses and smooth Hindi/Indian English intonation",
                checked = settings.naturalProsodyEnabled,
                onCheckedChange = {
                    onUpdateVoiceParameters(speed, pitch, volume, it)
                }
            )

            HorizontalDivider(color = NavyCardBorder)

            // Speed Slider
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Speech Rate (Speed)", style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
                    Text(
                        String.format(Locale.US, "%.2fx", speed),
                        style = MaterialTheme.typography.labelLarge,
                        color = EmeraldCharge
                    )
                }
                Slider(
                    value = speed,
                    onValueChange = { speed = it },
                    onValueChangeFinished = { onUpdateVoiceParameters(speed, pitch, volume, null) },
                    valueRange = 0.55f..1.75f,
                    colors = SliderDefaults.colors(
                        thumbColor = EmeraldCharge,
                        activeTrackColor = EmeraldCharge
                    ),
                    modifier = Modifier.testTag("slider_voice_speed")
                )
            }

            // Pitch Slider
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Voice Pitch (Resonance)", style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
                    Text(
                        String.format(Locale.US, "%.2fx", pitch),
                        style = MaterialTheme.typography.labelLarge,
                        color = ElectricCyan
                    )
                }
                Slider(
                    value = pitch,
                    onValueChange = { pitch = it },
                    onValueChangeFinished = { onUpdateVoiceParameters(speed, pitch, volume, null) },
                    valueRange = 0.6f..1.6f,
                    colors = SliderDefaults.colors(
                        thumbColor = ElectricCyan,
                        activeTrackColor = ElectricCyan
                    ),
                    modifier = Modifier.testTag("slider_voice_pitch")
                )
            }

            // Volume Slider
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Announcement Volume", style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
                    Text(
                        "${(volume * 100).roundToInt()}%",
                        style = MaterialTheme.typography.labelLarge,
                        color = PurpleVoiceAccent
                    )
                }
                Slider(
                    value = volume,
                    onValueChange = { volume = it },
                    onValueChangeFinished = { onUpdateVoiceParameters(speed, pitch, volume, null) },
                    valueRange = 0.1f..1.0f,
                    colors = SliderDefaults.colors(
                        thumbColor = PurpleVoiceAccent,
                        activeTrackColor = PurpleVoiceAccent
                    ),
                    modifier = Modifier.testTag("slider_voice_volume")
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = { onTestVoice(null) },
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("test_voice_button"),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = EmeraldCharge,
                        contentColor = Color(0xFF00210E)
                    )
                ) {
                    Icon(imageVector = Icons.Default.VolumeUp, contentDescription = "Test Voice")
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isSpeaking) "SPEAKING..." else "TEST ACTIVE VOICE (${settings.activePreset.title.uppercase(Locale.US)})",
                        style = MaterialTheme.typography.labelLarge
                    )
                }

                if (isSpeaking) {
                    OutlinedButton(
                        onClick = onStopSpeaking,
                        modifier = Modifier.height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, ThermalCrimson)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Stop,
                            contentDescription = "Stop",
                            tint = ThermalCrimson
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OfflineVoiceModelManagerCard(
    voiceModelPacks: List<OfflineVoiceModelPack>,
    selectedModelId: String,
    onInstallOrVerifyModel: (String) -> Unit,
    onOpenSystemTtsInstaller: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = NavySurface),
        border = BorderStroke(1.dp, NavyCardBorder)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.SettingsVoice,
                    contentDescription = "Offline Voice Models",
                    tint = ElectricCyan
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "OFFLINE NEURAL MODEL REGISTRY",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Piper VITS / Sherpa-ONNX Models & System Offline TTS Binding (Cached Once)",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            }

            voiceModelPacks.forEach { pack ->
                val isSelected = pack.modelId == selectedModelId
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = if (isSelected) NavySurfaceElevated else Color(0xFF0B111E),
                    border = BorderStroke(
                        width = 1.dp,
                        color = if (isSelected) ElectricCyan else NavyCardBorder
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = pack.displayName,
                                style = MaterialTheme.typography.titleSmall,
                                color = if (isSelected) ElectricCyan else TextPrimary,
                                fontWeight = FontWeight.Bold
                            )
                            Surface(
                                color = EmeraldCharge.copy(alpha = 0.16f),
                                shape = RoundedCornerShape(50)
                            ) {
                                Text(
                                    text = pack.state.label,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = EmeraldCharge,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                        }

                        Text(
                            text = "Model ID: ${pack.modelId} • ${pack.sampleRateHz} Hz • ${pack.sizeMb} MB",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                        Text(
                            text = "Voice Binding: ${pack.systemOfflineVoiceMatched ?: "Local Neural Acoustic Engine"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                        Text(
                            text = "License: ${pack.licenseName} (${pack.licenseAttribution})",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted
                        )
                    }
                }
            }

            OutlinedButton(
                onClick = onOpenSystemTtsInstaller,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("open_system_tts_button"),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.6f))
            ) {
                Icon(
                    imageVector = Icons.Default.Download,
                    contentDescription = "Manage Android Offline Voice Data",
                    tint = ElectricCyan
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "CHECK / INSTALL SYSTEM OFFLINE TTS VOICES",
                    style = MaterialTheme.typography.labelLarge,
                    color = ElectricCyan
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChargingAnnouncementRulesCard(
    settings: UserSettings,
    onUpdateChargingAlerts: (
        intervalPercent: Int?,
        intervalEnabled: Boolean?,
        smartMilestones: Boolean?,
        fullCharge: Boolean?,
        chargerConnected: Boolean?,
        chargerDisconnected: Boolean?,
        lowBattery: Boolean?,
        lowBatteryThreshold: Int?,
        tempWarning: Boolean?,
        tempWarningCelsius: Int?
    ) -> Unit
) {
    var customIntervalSlider by remember(settings.announceIntervalPercent) {
        mutableFloatStateOf(settings.announceIntervalPercent.toFloat())
    }
    var lowBatterySlider by remember(settings.lowBatteryThreshold) {
        mutableFloatStateOf(settings.lowBatteryThreshold.toFloat())
    }
    var tempThresholdSlider by remember(settings.temperatureWarningCelsius) {
        mutableFloatStateOf(settings.temperatureWarningCelsius.toFloat())
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = NavySurface),
        border = BorderStroke(1.dp, NavyCardBorder)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.NotificationsActive,
                    contentDescription = "Charging Alert Settings",
                    tint = EmeraldCharge
                )
                Column {
                    Text(
                        text = "SMART BOSS MILESTONES & TRIGGERS",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Configure 20%, 50%, 80%, 100% conversational milestones & intervals",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            }

            SettingSwitchRow(
                title = "Smart Conversational Milestones (20%, 50%, 80%)",
                subtitle = "Speaks natural milestone briefings (e.g. 'बॉस, अब बैटरी आधी चार्ज हो चुकी है।')",
                checked = settings.announceSmartMilestones,
                onCheckedChange = {
                    onUpdateChargingAlerts(null, null, it, null, null, null, null, null, null, null)
                }
            )

            HorizontalDivider(color = NavyCardBorder)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Announce Every ${settings.announceIntervalPercent}% While Charging",
                        style = MaterialTheme.typography.bodyLarge,
                        color = TextPrimary,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Select 5%, 10%, 15%, 20% or custom step",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
                Switch(
                    checked = settings.announceIntervalEnabled,
                    onCheckedChange = {
                        onUpdateChargingAlerts(null, it, null, null, null, null, null, null, null, null)
                    }
                )
            }

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(5, 10, 15, 20, 25).forEach { step ->
                    val selected = settings.announceIntervalPercent == step
                    FilterChip(
                        selected = selected,
                        onClick = {
                            customIntervalSlider = step.toFloat()
                            onUpdateChargingAlerts(step, true, null, null, null, null, null, null, null, null)
                        },
                        label = { Text("Every $step%") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = EmeraldCharge.copy(alpha = 0.2f),
                            selectedLabelColor = EmeraldCharge
                        )
                    )
                }
            }

            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Custom Interval Step", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                    Text(
                        "${customIntervalSlider.roundToInt()}%",
                        style = MaterialTheme.typography.labelLarge,
                        color = EmeraldCharge
                    )
                }
                Slider(
                    value = customIntervalSlider,
                    onValueChange = { customIntervalSlider = it },
                    onValueChangeFinished = {
                        onUpdateChargingAlerts(
                            customIntervalSlider.roundToInt(),
                            true, null, null, null, null, null, null, null, null
                        )
                    },
                    valueRange = 1f..50f,
                    colors = SliderDefaults.colors(
                        thumbColor = EmeraldCharge,
                        activeTrackColor = EmeraldCharge
                    )
                )
            }

            HorizontalDivider(color = NavyCardBorder)

            SettingSwitchRow(
                title = "Charger Connected Announcement",
                subtitle = "Speaks immediately when AC, USB, or Wireless power is connected",
                checked = settings.announceChargerConnected,
                onCheckedChange = {
                    onUpdateChargingAlerts(null, null, null, null, it, null, null, null, null, null)
                }
            )

            SettingSwitchRow(
                title = "Charger Disconnected Announcement",
                subtitle = "Speaks when power cable is unplugged",
                checked = settings.announceChargerDisconnected,
                onCheckedChange = {
                    onUpdateChargingAlerts(null, null, null, null, null, it, null, null, null, null)
                }
            )

            SettingSwitchRow(
                title = "Full Charge (100%) Announcement",
                subtitle = "Alerts you when battery reaches 100% full capacity",
                checked = settings.announceFullCharge,
                onCheckedChange = {
                    onUpdateChargingAlerts(null, null, null, it, null, null, null, null, null, null)
                }
            )

            HorizontalDivider(color = NavyCardBorder)

            SettingSwitchRow(
                title = "Low Battery Warning (${settings.lowBatteryThreshold}%)",
                subtitle = "Warns when battery drops below threshold while discharging",
                checked = settings.announceLowBattery,
                onCheckedChange = {
                    onUpdateChargingAlerts(null, null, null, null, null, null, it, null, null, null)
                }
            )
            Slider(
                value = lowBatterySlider,
                onValueChange = { lowBatterySlider = it },
                onValueChangeFinished = {
                    onUpdateChargingAlerts(
                        null, null, null, null, null, null, null,
                        lowBatterySlider.roundToInt(),
                        null, null
                    )
                },
                valueRange = 5f..35f,
                colors = SliderDefaults.colors(
                    thumbColor = AmberWarning,
                    activeTrackColor = AmberWarning
                )
            )

            SettingSwitchRow(
                title = "High Temperature Warning (${settings.temperatureWarningCelsius}°C)",
                subtitle = "Conversational thermal alert ('बॉस, मोबाइल थोड़ा ज्यादा गर्म हो रहा है।')",
                checked = settings.announceTemperatureWarning,
                onCheckedChange = {
                    onUpdateChargingAlerts(null, null, null, null, null, null, null, null, it, null)
                }
            )
            Slider(
                value = tempThresholdSlider,
                onValueChange = { tempThresholdSlider = it },
                onValueChangeFinished = {
                    onUpdateChargingAlerts(
                        null, null, null, null, null, null, null, null, null,
                        tempThresholdSlider.roundToInt()
                    )
                },
                valueRange = 36f..50f,
                colors = SliderDefaults.colors(
                    thumbColor = AmberWarning,
                    activeTrackColor = AmberWarning
                )
            )
        }
    }
}

@Composable
private fun QuietHoursAndRingerCard(
    settings: UserSettings,
    onUpdateQuietHoursAndRinger: (
        quietHoursEnabled: Boolean?,
        quietStartHour: Int?,
        quietEndHour: Int?,
        respectSystemRinger: Boolean?
    ) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = NavySurface),
        border = BorderStroke(1.dp, NavyCardBorder)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.NightlightRound,
                    contentDescription = "Quiet Hours",
                    tint = PurpleVoiceAccent
                )
                Column {
                    Text(
                        text = "QUIET HOURS & RINGER MODE",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Prevent sleep disruptions or follow phone Silent/Vibrate state",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            }

            SettingSwitchRow(
                title = "Nighttime Quiet Hours (${formatHour(settings.quietStartHour)} – ${formatHour(settings.quietEndHour)})",
                subtitle = "Mute automatic voice alerts during scheduled sleeping hours",
                checked = settings.quietHoursEnabled,
                onCheckedChange = {
                    onUpdateQuietHoursAndRinger(it, null, null, null)
                }
            )

            if (settings.quietHoursEnabled) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Start: ${formatHour(settings.quietStartHour)}", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                        Slider(
                            value = settings.quietStartHour.toFloat(),
                            onValueChange = {
                                onUpdateQuietHoursAndRinger(null, it.roundToInt(), null, null)
                            },
                            valueRange = 0f..23f,
                            steps = 22
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text("End: ${formatHour(settings.quietEndHour)}", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                        Slider(
                            value = settings.quietEndHour.toFloat(),
                            onValueChange = {
                                onUpdateQuietHoursAndRinger(null, null, it.roundToInt(), null)
                            },
                            valueRange = 0f..23f,
                            steps = 22
                        )
                    }
                }
            }

            SettingSwitchRow(
                title = "Respect System Silent / Vibrate Mode",
                subtitle = "When enabled, voice announcements are muted if your phone ringer is on Silent or Vibrate",
                checked = settings.respectSystemRinger,
                onCheckedChange = {
                    onUpdateQuietHoursAndRinger(null, null, null, it)
                }
            )
        }
    }
}

@Composable
private fun CustomMessagesEditorCard(
    settings: UserSettings,
    onSaveCustomMessages: (Boolean, String, String, String, String, String, String, String, String, String) -> Unit,
    onResetCustomMessages: () -> Unit,
    onPreviewPhrase: (String) -> Unit
) {
    val isHindi = settings.language == VoiceLanguage.HINDI

    var connectedText by remember(isHindi, settings) {
        mutableStateOf(if (isHindi) settings.msgHiConnected else settings.msgEnConnected)
    }
    var milestone20Text by remember(isHindi, settings) {
        mutableStateOf(if (isHindi) settings.msgHi20Percent else settings.msgEn20Percent)
    }
    var milestone50Text by remember(isHindi, settings) {
        mutableStateOf(if (isHindi) settings.msgHi50Percent else settings.msgEn50Percent)
    }
    var milestone80Text by remember(isHindi, settings) {
        mutableStateOf(if (isHindi) settings.msgHi80Percent else settings.msgEn80Percent)
    }
    var intervalText by remember(isHindi, settings) {
        mutableStateOf(if (isHindi) settings.msgHiInterval else settings.msgEnInterval)
    }
    var fullText by remember(isHindi, settings) {
        mutableStateOf(if (isHindi) settings.msgHiFull else settings.msgEnFull)
    }
    var highTempText by remember(isHindi, settings) {
        mutableStateOf(if (isHindi) settings.msgHiHighTemp else settings.msgEnHighTemp)
    }
    var disconnectedText by remember(isHindi, settings) {
        mutableStateOf(if (isHindi) settings.msgHiDisconnected else settings.msgEnDisconnected)
    }
    var lowText by remember(isHindi, settings) {
        mutableStateOf(if (isHindi) settings.msgHiLowBattery else settings.msgEnLowBattery)
    }
    var savedFeedback by remember { mutableStateOf(false) }

    LaunchedEffect(savedFeedback) {
        if (savedFeedback) {
            kotlinx.coroutines.delay(2000)
            savedFeedback = false
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = NavySurface),
        border = BorderStroke(1.dp, NavyCardBorder)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.EditNote,
                    contentDescription = "Custom Announcement Messages",
                    tint = ElectricCyan
                )
                Column {
                    Text(
                        text = "SMART BOSS CUSTOM ANNOUNCEMENTS (${settings.language.displayName.uppercase(Locale.US)})",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Edit every milestone phrase • Use {level} for % and {temp} for °C",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            }

            CustomPhraseField(
                label = "Charger Connected",
                value = connectedText,
                onValueChange = { connectedText = it },
                onPreview = { onPreviewPhrase(connectedText) }
            )
            CustomPhraseField(
                label = "Battery 20% Milestone",
                value = milestone20Text,
                onValueChange = { milestone20Text = it },
                onPreview = { onPreviewPhrase(milestone20Text) }
            )
            CustomPhraseField(
                label = "Battery 50% Halfway Milestone",
                value = milestone50Text,
                onValueChange = { milestone50Text = it },
                onPreview = { onPreviewPhrase(milestone50Text) }
            )
            CustomPhraseField(
                label = "Battery 80% Milestone",
                value = milestone80Text,
                onValueChange = { milestone80Text = it },
                onPreview = { onPreviewPhrase(milestone80Text) }
            )
            CustomPhraseField(
                label = "Battery 100% Full Charge",
                value = fullText,
                onValueChange = { fullText = it },
                onPreview = { onPreviewPhrase(fullText) }
            )
            CustomPhraseField(
                label = "High Temperature Warning",
                value = highTempText,
                onValueChange = { highTempText = it },
                onPreview = { onPreviewPhrase(highTempText.replace("{temp}", "43")) }
            )
            CustomPhraseField(
                label = "Charger Disconnected",
                value = disconnectedText,
                onValueChange = { disconnectedText = it },
                onPreview = { onPreviewPhrase(disconnectedText) }
            )
            CustomPhraseField(
                label = "Custom Interval ({level}%)",
                value = intervalText,
                onValueChange = { intervalText = it },
                onPreview = { onPreviewPhrase(intervalText.replace("{level}", "60")) }
            )
            CustomPhraseField(
                label = "Low Battery Warning ({level}%)",
                value = lowText,
                onValueChange = { lowText = it },
                onPreview = { onPreviewPhrase(lowText.replace("{level}", "15")) }
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onResetCustomMessages,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.RestartAlt,
                        contentDescription = "Reset defaults",
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Reset Defaults")
                }
                Button(
                    onClick = {
                        onSaveCustomMessages(
                            isHindi,
                            connectedText,
                            disconnectedText,
                            milestone20Text,
                            milestone50Text,
                            milestone80Text,
                            intervalText,
                            fullText,
                            lowText,
                            highTempText
                        )
                        savedFeedback = true
                    },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("save_custom_messages_button"),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = EmeraldCharge,
                        contentColor = Color(0xFF00210E)
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Save,
                        contentDescription = "Save phrases",
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(if (savedFeedback) "Saved!" else "Save All Phrases")
                }
            }
        }
    }
}

@Composable
private fun CustomPhraseField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    onPreview: () -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        trailingIcon = {
            IconButton(onClick = onPreview) {
                Icon(
                    imageVector = Icons.Default.VolumeUp,
                    contentDescription = "Preview $label",
                    tint = EmeraldCharge
                )
            }
        },
        modifier = Modifier.fillMaxWidth(),
        singleLine = false,
        maxLines = 2,
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = EmeraldCharge,
            unfocusedBorderColor = NavyCardBorder,
            focusedLabelColor = EmeraldCharge,
            unfocusedLabelColor = TextSecondary
        )
    )
}

@Composable
fun SettingSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = TextPrimary,
                fontWeight = FontWeight.Medium
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
                checkedTrackColor = EmeraldCharge,
                uncheckedThumbColor = TextSecondary,
                uncheckedTrackColor = NavySurfaceElevated
            )
        )
    }
}

private fun formatHour(hour24: Int): String {
    val h = hour24.coerceIn(0, 23)
    val amPm = if (h < 12) "AM" else "PM"
    val h12 = when {
        h == 0 -> 12
        h > 12 -> h - 12
        else -> h
    }
    return "$h12:00 $amPm"
}
