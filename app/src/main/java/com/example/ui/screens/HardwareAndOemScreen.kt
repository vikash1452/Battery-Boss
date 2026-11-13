package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeveloperBoard
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.model.BatteryHardwareSnapshot
import com.example.model.OemOptimizationGuide
import com.example.model.UserSettings
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

import com.example.model.AudioDiagnosticsState

@Composable
fun HardwareAndOemScreen(
    snapshot: BatteryHardwareSnapshot,
    settings: UserSettings,
    audioDiagnostics: AudioDiagnosticsState,
    isServiceRunning: Boolean,
    isIgnoringBatteryOptimizations: Boolean,
    oemGuide: OemOptimizationGuide,
    onToggleMonitoring: (Boolean) -> Unit,
    onUpdateBackgroundSettings: (Boolean?, Boolean?) -> Unit,
    onRefreshOptimizationState: () -> Unit,
    onRunQuickVoiceTest: () -> Unit,
    onRunQueueBurstTest: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("hardware_oem_screen"),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. Background Operation & Foreground Service Controls
        item {
            BackgroundServiceControlCard(
                settings = settings,
                isServiceRunning = isServiceRunning,
                isIgnoringBatteryOptimizations = isIgnoringBatteryOptimizations,
                onToggleMonitoring = onToggleMonitoring,
                onUpdateBackgroundSettings = onUpdateBackgroundSettings,
                onOpenBatteryOptimizationSettings = {
                    runCatching {
                        val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        context.startActivity(intent)
                    }.onFailure {
                        runCatching {
                            val appDetails = Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", context.packageName, null)
                            ).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                            context.startActivity(appDetails)
                        }
                    }
                    onRefreshOptimizationState()
                },
                onOpenAppDetails = {
                    runCatching {
                        val appDetails = Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", context.packageName, null)
                        ).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        context.startActivity(appDetails)
                    }
                }
            )
        }

        // 2. Voice & Audio System Diagnostics (Section 10)
        item {
            AudioDiagnosticsCard(
                diagnostics = audioDiagnostics,
                onRefreshDiagnostics = onRefreshOptimizationState,
                onRunQuickVoiceTest = onRunQuickVoiceTest,
                onRunQueueBurstTest = onRunQueueBurstTest
            )
        }

        // 3. Detected OEM Background Survival Guide
        item {
            OemSurvivalGuideCard(oemGuide = oemGuide)
        }

        // 4. Complete Raw BatteryManager Hardware Diagnostics Table
        item {
            HardwareDiagnosticsTableCard(snapshot = snapshot)
        }
    }
}

@Composable
private fun BackgroundServiceControlCard(
    settings: UserSettings,
    isServiceRunning: Boolean,
    isIgnoringBatteryOptimizations: Boolean,
    onToggleMonitoring: (Boolean) -> Unit,
    onUpdateBackgroundSettings: (Boolean?, Boolean?) -> Unit,
    onOpenBatteryOptimizationSettings: () -> Unit,
    onOpenAppDetails: () -> Unit
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
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Security,
                    contentDescription = "Background Service Controls",
                    tint = EmeraldCharge
                )
                Column {
                    Text(
                        text = "BACKGROUND MONITORING & SERVICE",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Foreground Service keeps voice alerts active when app is closed or screen is off",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            }

            // Start / Stop Monitoring Action Button
            Button(
                onClick = { onToggleMonitoring(!isServiceRunning) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("start_stop_service_button"),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isServiceRunning) ThermalCrimson else EmeraldCharge,
                    contentColor = if (isServiceRunning) Color.White else Color(0xFF00210E)
                )
            ) {
                Icon(
                    imageVector = if (isServiceRunning) Icons.Default.Stop else Icons.Default.PlayArrow,
                    contentDescription = null
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isServiceRunning) "STOP BACKGROUND MONITORING" else "START BACKGROUND MONITORING",
                    style = MaterialTheme.typography.labelLarge
                )
            }

            SettingSwitchRow(
                title = "Start Automatically After Reboot",
                subtitle = "Resumes BatteryMonitorService on BOOT_COMPLETED when permitted by OS",
                checked = settings.startOnBootEnabled,
                onCheckedChange = { onUpdateBackgroundSettings(it, null) }
            )

            SettingSwitchRow(
                title = "Persistent Live Notification",
                subtitle = "Displays real-time battery %, voltage, temperature & 'Speak Status' action",
                checked = settings.persistentNotificationEnabled,
                onCheckedChange = { onUpdateBackgroundSettings(null, it) }
            )

            HorizontalDivider(color = NavyCardBorder)

            // Battery Optimization Exemption Status
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = NavySurfaceElevated,
                border = BorderStroke(
                    1.dp,
                    if (isIgnoringBatteryOptimizations) EmeraldCharge.copy(alpha = 0.5f) else AmberWarning.copy(alpha = 0.6f)
                )
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = if (isIgnoringBatteryOptimizations) Icons.Default.CheckCircle else Icons.Default.WarningAmber,
                            contentDescription = "Optimization Status",
                            tint = if (isIgnoringBatteryOptimizations) EmeraldCharge else AmberWarning
                        )
                        Text(
                            text = if (isIgnoringBatteryOptimizations) {
                                "Battery Optimization: Unrestricted (Recommended)"
                            } else {
                                "Battery Optimization: Active (May pause background voice)"
                            },
                            style = MaterialTheme.typography.titleSmall,
                            color = if (isIgnoringBatteryOptimizations) EmeraldCharge else AmberWarning,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        text = "Android Doze and OEM battery savers can suspend background services when the screen is off. Set Battery Boss to 'Unrestricted' in system settings for reliable overnight charging alerts.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = onOpenBatteryOptimizationSettings,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("open_battery_optimization_button"),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.BatterySaver,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Optimization List", style = MaterialTheme.typography.labelMedium)
                        }
                        OutlinedButton(
                            onClick = onOpenAppDetails,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("open_app_details_button"),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.OpenInNew,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("App Battery Info", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OemSurvivalGuideCard(oemGuide: OemOptimizationGuide) {
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
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.PhoneAndroid,
                    contentDescription = "OEM Guide",
                    tint = ElectricCyan
                )
                Column {
                    Text(
                        text = "DETECTED OEM: ${oemGuide.manufacturerName.uppercase(Locale.US)}",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Platform Profile: ${oemGuide.osSkinName} • Model: ${Build.MODEL}",
                        style = MaterialTheme.typography.bodySmall,
                        color = ElectricCyan
                    )
                }
            }

            oemGuide.steps.forEachIndexed { index, step ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Surface(
                        color = ElectricCyan.copy(alpha = 0.2f),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = "${index + 1}",
                            style = MaterialTheme.typography.labelMedium,
                            color = ElectricCyan,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                    Text(
                        text = step,
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextPrimary,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            Surface(
                color = NavySurfaceElevated,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, NavyCardBorder)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = "Important OEM Note",
                        tint = PurpleVoiceAccent,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = oemGuide.caveatNote,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            }
        }
    }
}

@Composable
private fun HardwareDiagnosticsTableCard(snapshot: BatteryHardwareSnapshot) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("hardware_diagnostics_card"),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = NavySurface),
        border = BorderStroke(1.dp, NavyCardBorder)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.DeveloperBoard,
                    contentDescription = "Raw Hardware Sensors",
                    tint = EmeraldCharge
                )
                Column {
                    Text(
                        text = "BATTERY HARDWARE DIAGNOSTICS",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Direct kernel & BatteryManager properties (Unreported sensors show N/A)",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            }

            val rows = listOf(
                "Battery Level" to "${snapshot.percentage}%",
                "Charging Status" to snapshot.statusLabel,
                "Power Source" to snapshot.chargingSource.displayName,
                "Battery Voltage" to (snapshot.voltageVolts?.let {
                    String.format(Locale.US, "%.3f V (%d mV)", it, snapshot.voltageMillivolts ?: 0)
                } ?: "N/A"),
                "Instant Current (CURRENT_NOW)" to (snapshot.currentMilliAmps?.let { "$it mA" } ?: "N/A"),
                "Average Current (CURRENT_AVG)" to (snapshot.averageCurrentMilliAmps?.let { "$it mA" } ?: "N/A"),
                "Charging / Discharge Power" to (snapshot.powerWatts?.let {
                    String.format(Locale.US, "%.2f W", abs(it))
                } ?: "N/A"),
                "Battery Temperature" to (snapshot.temperatureCelsius?.let {
                    String.format(Locale.US, "%.1f °C (%.1f °F)", it, it * 9f / 5f + 32f)
                } ?: "N/A"),
                "Battery Health" to snapshot.healthLabel,
                "Battery Chemistry / Tech" to (snapshot.technology ?: "N/A"),
                "Remaining Charge Counter" to (snapshot.chargeCounterMah?.let { "$it mAh" } ?: "N/A"),
                "Hardware Design Capacity" to (snapshot.designCapacityMah?.let { "$it mAh" } ?: "N/A"),
                "Estimated Time to Full" to (snapshot.estimatedMinutesToFull?.let { "$it min" } ?: "N/A"),
                "Battery Present" to if (snapshot.isBatteryPresent) "Yes" else "No",
                "Device Hardware" to "${Build.MANUFACTURER} ${Build.MODEL} (API ${Build.VERSION.SDK_INT})"
            )

            rows.forEachIndexed { index, (label, value) ->
                if (index > 0) {
                    HorizontalDivider(color = NavyCardBorder.copy(alpha = 0.5f))
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = value,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (value == "N/A") TextMuted else TextPrimary
                    )
                }
            }
        }
    }
}
