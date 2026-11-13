package com.example.ui.screens

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
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.DeveloperBoard
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.model.BatteryHardwareSnapshot
import com.example.model.DiagnosticMetric
import com.example.model.MetricConfidence
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

/**
 * PART 4 – Dedicated Battery Health Analysis & Deep Hardware Diagnostics Screen.
 *
 * Displays:
 * - Reported battery health condition (never claiming GOOD == 100% battery health).
 * - Battery temperature & thermal stress analysis.
 * - Charging cycle count (if exposed by Android 14+ or sysfs).
 * - Design capacity, Full charge capacity, Remaining capacity, and Energy counter.
 * - Battery degradation information ONLY when supported by genuine hardware measurements.
 * - Every metric includes its exact Hardware Source API and Confidence Level badge.
 */
@Composable
fun BatteryHealthScreen(
    snapshot: BatteryHardwareSnapshot,
    modifier: Modifier = Modifier
) {
    val report = snapshot.healthReport

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("battery_health_screen"),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. Primary OS Health Condition & Scientific Truth Callout
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = NavySurface),
                border = BorderStroke(1.dp, EmeraldCharge.copy(alpha = 0.5f))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.HealthAndSafety,
                                contentDescription = "Health Condition",
                                tint = EmeraldCharge,
                                modifier = Modifier.size(28.dp)
                            )
                            Column {
                                Text(
                                    text = "REPORTED HEALTH CONDITION",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = ElectricCyan
                                )
                                Text(
                                    text = report.reportedOsHealthStatus.uppercase(Locale.US),
                                    style = MaterialTheme.typography.headlineMedium,
                                    color = TextPrimary,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                        ConfidenceBadge(confidence = MetricConfidence.QUALITATIVE_OS)
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
                                contentDescription = "Scientific Honesty Note",
                                tint = AmberWarning,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = report.osHealthExplanation,
                                style = MaterialTheme.typography.bodySmall,
                                color = TextPrimary
                            )
                        }
                    }
                }
            }
        }

        // 2. Capacity, Cycle Count & Degradation Analysis Card
        item {
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
                            imageVector = Icons.Default.Analytics,
                            contentDescription = "Capacity & Degradation Analysis",
                            tint = ElectricCyan
                        )
                        Column {
                            Text(
                                text = "CAPACITY, CYCLES & DEGRADATION ANALYSIS",
                                style = MaterialTheme.typography.titleMedium,
                                color = TextPrimary,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Source-verified coulomb counter & kernel cycle registers",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                        }
                    }

                    HealthSourceMetricRow(
                        label = "Charging Cycle Count",
                        value = report.cycleCount?.let { "$it cycles" } ?: "N/A",
                        source = report.cycleCountSource,
                        confidence = if (report.cycleCount != null) {
                            MetricConfidence.HIGH_DIRECT
                        } else {
                            MetricConfidence.UNAVAILABLE
                        }
                    )

                    HorizontalDivider(color = NavyCardBorder.copy(alpha = 0.5f))

                    HealthSourceMetricRow(
                        label = "Factory Design Capacity",
                        value = report.designCapacityMah?.let { "$it mAh" } ?: "N/A",
                        source = report.designCapacitySource,
                        confidence = if (report.designCapacityMah != null) {
                            MetricConfidence.HIGH_DIRECT
                        } else {
                            MetricConfidence.UNAVAILABLE
                        }
                    )

                    HorizontalDivider(color = NavyCardBorder.copy(alpha = 0.5f))

                    HealthSourceMetricRow(
                        label = "Measured Full Charge Capacity",
                        value = report.fullChargeCapacityMah?.let { "$it mAh" } ?: "N/A",
                        source = report.fullChargeCapacitySource,
                        confidence = if (report.fullChargeCapacityMah != null) {
                            MetricConfidence.MEDIUM_DERIVED
                        } else {
                            MetricConfidence.UNAVAILABLE
                        }
                    )

                    HorizontalDivider(color = NavyCardBorder.copy(alpha = 0.5f))

                    HealthSourceMetricRow(
                        label = "Remaining Capacity (Live Coulomb Counter)",
                        value = report.remainingCapacityMah?.let { "$it mAh" } ?: "N/A",
                        source = report.remainingCapacitySource,
                        confidence = if (report.remainingCapacityMah != null) {
                            MetricConfidence.HIGH_DIRECT
                        } else {
                            MetricConfidence.UNAVAILABLE
                        }
                    )

                    HorizontalDivider(color = NavyCardBorder.copy(alpha = 0.5f))

                    HealthSourceMetricRow(
                        label = "Measured Capacity Retention (Wear Estimate)",
                        value = report.measuredCapacityHealthPercent?.let {
                            String.format(Locale.US, "%.1f%%", it)
                        } ?: "N/A",
                        source = if (report.measuredCapacityHealthPercent != null) {
                            "Ratio: Full Charge Capacity / Factory Design Capacity"
                        } else {
                            "Requires hardware charge_full & design_capacity registers"
                        },
                        confidence = if (report.measuredCapacityHealthPercent != null) {
                            MetricConfidence.MEDIUM_DERIVED
                        } else {
                            MetricConfidence.UNAVAILABLE
                        }
                    )

                    Surface(
                        color = NavySurfaceElevated,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, NavyCardBorder)
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = "DEGRADATION & WEAR ASSESSMENT",
                                style = MaterialTheme.typography.labelSmall,
                                color = PurpleVoiceAccent
                            )
                            Text(
                                text = report.degradationSummary,
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                        }
                    }
                }
            }
        }

        // 3. Thermal Condition & Cell Chemistry Card
        item {
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
                            imageVector = Icons.Default.Thermostat,
                            contentDescription = "Thermal Analysis",
                            tint = AmberWarning
                        )
                        Column {
                            Text(
                                text = "THERMAL & ELECTRICAL STRESS PROFILE",
                                style = MaterialTheme.typography.titleMedium,
                                color = TextPrimary,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = report.temperatureStatus,
                                style = MaterialTheme.typography.bodySmall,
                                color = AmberWarning
                            )
                        }
                    }

                    Text(
                        text = "Battery-Side vs. Wall Charger Input Power:",
                        style = MaterialTheme.typography.titleSmall,
                        color = ElectricCyan,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Android's BatteryManager measures current and voltage directly at the battery cell terminals (${snapshot.voltageVolts?.let { String.format(Locale.US, "%.2f V", it) } ?: "N/A"}). When charging with a fast adapter, total wall/USB-C input power is higher than measured cell-side power (${snapshot.powerWatts?.let { String.format(Locale.US, "%.2f W", abs(it)) } ?: "N/A"}) because part of the adapter's power runs the display/SoC and ~8–12% is dissipated as heat in the PMIC step-down converter.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            }
        }

        // 4. Complete Source & Confidence Diagnostic Register List
        item {
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
                            imageVector = Icons.Default.DeveloperBoard,
                            contentDescription = "Hardware Diagnostic Registers",
                            tint = EmeraldCharge
                        )
                        Column {
                            Text(
                                text = "COMPLETE HARDWARE DIAGNOSTIC REGISTERS",
                                style = MaterialTheme.typography.titleMedium,
                                color = TextPrimary,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Every metric lists its Android API / Kernel origin & confidence",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                        }
                    }

                    snapshot.diagnosticMetrics.forEachIndexed { idx, metric ->
                        if (idx > 0) {
                            HorizontalDivider(color = NavyCardBorder.copy(alpha = 0.5f))
                        }
                        DiagnosticMetricItem(metric = metric)
                    }
                }
            }
        }
    }
}

@Composable
private fun HealthSourceMetricRow(
    label: String,
    value: String,
    source: String,
    confidence: MetricConfidence
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                color = if (value == "N/A") TextMuted else EmeraldCharge,
                fontWeight = FontWeight.Bold
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Source: $source",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            ConfidenceBadge(confidence = confidence)
        }
    }
}

@Composable
private fun DiagnosticMetricItem(metric: DiagnosticMetric) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = metric.title,
                style = MaterialTheme.typography.bodyMedium,
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = metric.value,
                style = MaterialTheme.typography.labelLarge,
                color = if (metric.value == "N/A") TextMuted else ElectricCyan
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "API: ${metric.sourceApi}",
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted
            )
            ConfidenceBadge(confidence = metric.confidence)
        }
        Text(
            text = metric.explanation,
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary
        )
    }
}

@Composable
private fun ConfidenceBadge(confidence: MetricConfidence) {
    val badgeColor = when (confidence) {
        MetricConfidence.HIGH_DIRECT -> EmeraldCharge
        MetricConfidence.MEDIUM_DERIVED -> ElectricCyan
        MetricConfidence.QUALITATIVE_OS -> AmberWarning
        MetricConfidence.UNAVAILABLE -> TextMuted
    }
    Surface(
        color = badgeColor.copy(alpha = 0.14f),
        shape = RoundedCornerShape(50),
        border = BorderStroke(1.dp, badgeColor.copy(alpha = 0.4f))
    ) {
        Text(
            text = confidence.badgeText,
            style = MaterialTheme.typography.labelSmall,
            color = badgeColor,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
        )
    }
}
