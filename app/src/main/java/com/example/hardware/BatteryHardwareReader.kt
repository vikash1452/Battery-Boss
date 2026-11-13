package com.example.hardware

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import com.example.model.BatteryHardwareSnapshot
import com.example.model.BatteryHealthReport
import com.example.model.ChargingSource
import com.example.model.ChargingSpeedTier
import com.example.model.DiagnosticMetric
import com.example.model.MetricConfidence
import com.example.model.OemOptimizationGuide
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Deep Battery Hardware Diagnostics Reader for Battery Boss 2.0.
 *
 * Reads genuine hardware registers from Android's BatteryManager, sticky ACTION_BATTERY_CHANGED
 * broadcasts, and SELinux-permitted /sys/class/power_supply/battery/ nodes.
 *
 * CRITICAL GUARANTEE:
 * - Never invents, simulates, or fabricates battery health %, capacity, current, voltage, wattage, or cycle count.
 * - Clearly distinguishes measured battery-side cell power (V_cell × I_cell) from wall charger input power.
 * - Never equates Android's qualitative GOOD health status flag with "100% battery health".
 */
class BatteryHardwareReader(private val context: Context) {

    private val batteryManager: BatteryManager? =
        context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager

    // Caches full-charge coulomb counter if observed at 100% during runtime
    @Volatile
    private var observedFullChargeMah: Int? = null

    fun readSnapshot(batteryIntent: Intent? = null): BatteryHardwareSnapshot {
        val intent = batteryIntent ?: context.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )

        if (intent == null) {
            return BatteryHardwareSnapshot(statusLabel = "N/A")
        }

        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val percentage = if (level >= 0 && scale > 0) {
            ((level.toFloat() / scale.toFloat()) * 100f).roundToInt().coerceIn(0, 100)
        } else {
            val propCapacity = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
            if (propCapacity in 0..100) propCapacity else 0
        }

        val statusInt = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
        val pluggedInt = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        val chargingSource = mapChargingSource(pluggedInt)

        val isCharging = statusInt == BatteryManager.BATTERY_STATUS_CHARGING ||
            (pluggedInt != 0 && statusInt == BatteryManager.BATTERY_STATUS_FULL)
        val isFull = statusInt == BatteryManager.BATTERY_STATUS_FULL || (percentage == 100 && pluggedInt != 0)

        val statusLabel = when {
            isFull -> "Fully Charged"
            statusInt == BatteryManager.BATTERY_STATUS_CHARGING -> "Charging"
            statusInt == BatteryManager.BATTERY_STATUS_DISCHARGING -> "Discharging"
            statusInt == BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "Not Charging (Plugged)"
            pluggedInt != 0 -> "Connected (${chargingSource.displayName})"
            else -> "Discharging"
        }

        // 1. Voltage (EXTRA_VOLTAGE is reported in mV, or V on rare older kernels)
        val rawVoltage = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
        val voltageMillivolts: Int? = when {
            rawVoltage <= 0 -> readSysfsInt("/sys/class/power_supply/battery/voltage_now")?.let {
                if (it > 100_000) it / 1000 else it
            }
            rawVoltage < 20 -> rawVoltage * 1000
            else -> rawVoltage
        }
        val voltageVolts: Float? = voltageMillivolts?.let { it / 1000f }

        // 2. Temperature (EXTRA_TEMPERATURE is reported in tenths of °C)
        val rawTemp = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        val temperatureCelsius: Float? = if (rawTemp != Int.MIN_VALUE && rawTemp in -400..1200 && rawTemp != 0) {
            rawTemp / 10f
        } else {
            null
        }

        // 3. Instant & Average Battery Current (BATTERY_PROPERTY_CURRENT_NOW & CURRENT_AVERAGE)
        val rawCurrentNow = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        val currentMilliAmps: Int? = normalizeCurrentToMilliAmps(rawCurrentNow)
            ?: readSysfsInt("/sys/class/power_supply/battery/current_now")?.let { normalizeCurrentToMilliAmps(it) }

        val rawCurrentAvg = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE)
        val averageCurrentMilliAmps: Int? = normalizeCurrentToMilliAmps(rawCurrentAvg)

        // 4. Measured Battery-Side Power (W = V_cell * I_cell)
        val powerWatts: Float? = if (voltageVolts != null && currentMilliAmps != null && currentMilliAmps != 0) {
            val amps = abs(currentMilliAmps) / 1000f
            voltageVolts * amps
        } else {
            null
        }

        val averagePowerWatts: Float? = if (voltageVolts != null && averageCurrentMilliAmps != null && averageCurrentMilliAmps != 0) {
            val amps = abs(averageCurrentMilliAmps) / 1000f
            voltageVolts * amps
        } else {
            null
        }

        // 5. Charge Counter (Remaining Capacity in mAh via BATTERY_PROPERTY_CHARGE_COUNTER)
        val rawChargeCounterUah = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        val chargeCounterMah: Int? = if (
            rawChargeCounterUah != null &&
            rawChargeCounterUah != Int.MIN_VALUE &&
            rawChargeCounterUah > 0
        ) {
            if (rawChargeCounterUah > 50_000) {
                (rawChargeCounterUah / 1000f).roundToInt()
            } else {
                rawChargeCounterUah
            }
        } else {
            null
        }

        if (percentage == 100 && chargeCounterMah != null && chargeCounterMah in 1000..25000) {
            observedFullChargeMah = chargeCounterMah
        }

        // 6. Energy Counter (BATTERY_PROPERTY_ENERGY_COUNTER in nanowatt-hours -> Wh)
        val rawEnergyNwh = batteryManager?.getLongProperty(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER)
        val energyCounterWh: Float? = if (rawEnergyNwh != null && rawEnergyNwh != Long.MIN_VALUE && rawEnergyNwh > 0L) {
            (rawEnergyNwh.toDouble() / 1_000_000_000.0).toFloat()
        } else {
            null
        }

        // 7. Cycle Count (Intent extra "android.os.extra.CYCLE_COUNT" on Android 14+ or sysfs)
        val intentCycleCount = intent.getIntExtra("android.os.extra.CYCLE_COUNT", -1)
        val sysfsCycleCount = readSysfsInt("/sys/class/power_supply/battery/cycle_count")
        val cycleCount: Int? = when {
            intentCycleCount > 0 -> intentCycleCount
            sysfsCycleCount != null && sysfsCycleCount > 0 -> sysfsCycleCount
            else -> null
        }
        val cycleCountSource = when {
            intentCycleCount > 0 -> "BatteryManager.EXTRA_CYCLE_COUNT (Android 14+)"
            sysfsCycleCount != null && sysfsCycleCount > 0 -> "/sys/class/power_supply/battery/cycle_count"
            else -> "Not exposed by OEM kernel / Android API"
        }

        // 8. Design Capacity & Full Charge Capacity
        val (designCapacityMah, designCapSource) = readDesignCapacityWithSource()
        val (fullChargeCapacityMah, fullCapSource) = readFullChargeCapacityWithSource()

        // 9. Charging Speed Classification & %/hr Rate
        val chargingSpeedTier = classifyChargingSpeed(isCharging, isFull, powerWatts)
        val percentPerHourRate: Float? = computePercentPerHourRate(
            currentMilliAmps = currentMilliAmps,
            referenceCapacityMah = fullChargeCapacityMah ?: designCapacityMah
        )

        // 10. Estimated Time to Full
        val estimatedMinutesToFull = computeReliableChargeTimeMinutes(
            isCharging = isCharging,
            isFull = isFull,
            percentage = percentage,
            chargeCounterMah = chargeCounterMah,
            fullOrDesignCapacityMah = fullChargeCapacityMah ?: designCapacityMah,
            currentMilliAmps = currentMilliAmps
        )

        val healthInt = intent.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN)
        val healthLabel = mapHealthLabel(healthInt)

        val rawTech = intent.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY)?.trim()
        val technology: String? = if (!rawTech.isNullOrEmpty()) rawTech else null
        val isPresent = intent.getBooleanExtra(BatteryManager.EXTRA_PRESENT, true)

        val healthReport = buildBatteryHealthReport(
            healthLabel = healthLabel,
            temperatureCelsius = temperatureCelsius,
            cycleCount = cycleCount,
            cycleCountSource = cycleCountSource,
            designCapacityMah = designCapacityMah,
            designCapSource = designCapSource,
            fullChargeCapacityMah = fullChargeCapacityMah,
            fullCapSource = fullCapSource,
            chargeCounterMah = chargeCounterMah,
            energyCounterWh = energyCounterWh
        )

        val diagnosticMetrics = buildDiagnosticMetricsList(
            percentage = percentage,
            statusLabel = statusLabel,
            healthLabel = healthLabel,
            voltageVolts = voltageVolts,
            voltageMillivolts = voltageMillivolts,
            currentMilliAmps = currentMilliAmps,
            averageCurrentMilliAmps = averageCurrentMilliAmps,
            powerWatts = powerWatts,
            temperatureCelsius = temperatureCelsius,
            technology = technology,
            chargingSource = chargingSource,
            chargeCounterMah = chargeCounterMah,
            cycleCount = cycleCount,
            cycleCountSource = cycleCountSource,
            designCapacityMah = designCapacityMah,
            designCapSource = designCapSource,
            fullChargeCapacityMah = fullChargeCapacityMah,
            fullCapSource = fullCapSource,
            energyCounterWh = energyCounterWh,
            estimatedMinutesToFull = estimatedMinutesToFull,
            chargingSpeedTier = chargingSpeedTier
        )

        return BatteryHardwareSnapshot(
            percentage = percentage,
            isCharging = isCharging,
            isFull = isFull,
            statusLabel = statusLabel,
            chargingSource = chargingSource,
            voltageVolts = voltageVolts,
            voltageMillivolts = voltageMillivolts,
            currentMilliAmps = currentMilliAmps,
            averageCurrentMilliAmps = averageCurrentMilliAmps,
            powerWatts = powerWatts,
            averagePowerWatts = averagePowerWatts,
            temperatureCelsius = temperatureCelsius,
            healthLabel = healthLabel,
            technology = technology,
            chargeCounterMah = chargeCounterMah,
            fullChargeCapacityMah = fullChargeCapacityMah,
            designCapacityMah = designCapacityMah,
            cycleCount = cycleCount,
            energyCounterWh = energyCounterWh,
            estimatedMinutesToFull = estimatedMinutesToFull,
            chargingSpeedTier = chargingSpeedTier,
            percentPerHourRate = percentPerHourRate,
            isBatteryPresent = isPresent,
            healthReport = healthReport,
            diagnosticMetrics = diagnosticMetrics,
            timestampMillis = System.currentTimeMillis()
        )
    }

    private fun normalizeCurrentToMilliAmps(rawValue: Int?): Int? {
        if (rawValue == null || rawValue == Int.MIN_VALUE || rawValue == 0) return null
        val absVal = abs(rawValue)
        return when {
            absVal >= 15_000 -> (rawValue / 1000f).roundToInt()
            else -> rawValue
        }
    }

    private fun readDesignCapacityWithSource(): Pair<Int?, String> {
        // 1. Check sysfs charge_full_design if readable
        val sysfsDesign = readSysfsInt("/sys/class/power_supply/battery/charge_full_design")
        if (sysfsDesign != null && sysfsDesign > 500) {
            val mah = if (sysfsDesign > 50_000) (sysfsDesign / 1000f).roundToInt() else sysfsDesign
            if (mah in 1000..30000) {
                return mah to "/sys/class/power_supply/battery/charge_full_design"
            }
        }

        // 2. Check Android PowerProfile if accessible
        try {
            val powerProfileClass = Class.forName("com.android.internal.os.PowerProfile")
            val powerProfile = powerProfileClass.getConstructor(Context::class.java).newInstance(context)
            val capacity = powerProfileClass.getMethod("getBatteryCapacity").invoke(powerProfile) as? Double
            if (capacity != null && capacity > 500.0 && capacity < 30000.0) {
                return capacity.roundToInt() to "Android OS PowerProfile (Factory Spec)"
            }
        } catch (_: Throwable) {
            // Restricted on some builds
        }

        return null to "N/A — Restricted by SELinux / Not exposed by OEM kernel"
    }

    private fun readFullChargeCapacityWithSource(): Pair<Int?, String> {
        val sysfsFull = readSysfsInt("/sys/class/power_supply/battery/charge_full")
        if (sysfsFull != null && sysfsFull > 500) {
            val mah = if (sysfsFull > 50_000) (sysfsFull / 1000f).roundToInt() else sysfsFull
            if (mah in 1000..30000) {
                return mah to "/sys/class/power_supply/battery/charge_full (Fuel Gauge)"
            }
        }

        val cachedFull = observedFullChargeMah
        if (cachedFull != null) {
            return cachedFull to "BATTERY_PROPERTY_CHARGE_COUNTER measured at 100%"
        }

        return null to "N/A — Charge device to 100% or restricted by kernel SELinux"
    }

    private fun classifyChargingSpeed(
        isCharging: Boolean,
        isFull: Boolean,
        powerWatts: Float?
    ): ChargingSpeedTier {
        if (!isCharging && !isFull) return ChargingSpeedTier.NOT_CHARGING
        if (powerWatts == null) return ChargingSpeedTier.UNAVAILABLE
        val absWatts = abs(powerWatts)
        return when {
            absWatts < 5.0f -> ChargingSpeedTier.SLOW
            absWatts < 15.0f -> ChargingSpeedTier.NORMAL
            absWatts < 30.0f -> ChargingSpeedTier.FAST
            else -> ChargingSpeedTier.SUPER_FAST
        }
    }

    private fun computePercentPerHourRate(
        currentMilliAmps: Int?,
        referenceCapacityMah: Int?
    ): Float? {
        if (currentMilliAmps == null || referenceCapacityMah == null || referenceCapacityMah <= 0) {
            return null
        }
        val rate = (abs(currentMilliAmps).toFloat() / referenceCapacityMah.toFloat()) * 100f
        return if (rate in 0.5f..350f) rate else null
    }

    private fun computeReliableChargeTimeMinutes(
        isCharging: Boolean,
        isFull: Boolean,
        percentage: Int,
        chargeCounterMah: Int?,
        fullOrDesignCapacityMah: Int?,
        currentMilliAmps: Int?
    ): Int? {
        if (!isCharging || isFull || percentage >= 100) return null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val remainingMs = batteryManager?.computeChargeTimeRemaining() ?: -1L
            if (remainingMs > 0L) {
                val mins = (remainingMs / 60_000L).toInt()
                if (mins in 1..720) return mins
            }
        }

        if (chargeCounterMah != null && fullOrDesignCapacityMah != null && currentMilliAmps != null) {
            val absCurrentMa = abs(currentMilliAmps)
            val remainingMah = (fullOrDesignCapacityMah - chargeCounterMah).coerceAtLeast(0)
            if (absCurrentMa > 100 && remainingMah > 0) {
                val hours = remainingMah.toFloat() / absCurrentMa.toFloat()
                val mins = (hours * 60f * 1.15f).roundToInt()
                if (mins in 1..720) return mins
            }
        }
        return null
    }

    private fun buildBatteryHealthReport(
        healthLabel: String,
        temperatureCelsius: Float?,
        cycleCount: Int?,
        cycleCountSource: String,
        designCapacityMah: Int?,
        designCapSource: String,
        fullChargeCapacityMah: Int?,
        fullCapSource: String,
        chargeCounterMah: Int?,
        energyCounterWh: Float?
    ): BatteryHealthReport {
        val osExplanation = when (healthLabel) {
            "Good" -> "Android EXTRA_HEALTH reports GOOD. Important: This is a qualitative safety flag indicating no thermal/voltage fault—it does NOT mean 100% capacity retention."
            "Overheating" -> "Battery temperature exceeds safe operating envelope. Disconnect charger and let the phone cool down."
            "Over Voltage" -> "Battery voltage exceeds maximum cell threshold. Check charger cable and adapter."
            "Dead / Depleted" -> "Hardware reports severe cell depletion or failure."
            "Too Cold" -> "Battery cell is below minimum safe charging temperature."
            else -> "Hardware health status flag is not reported by this device."
        }

        val measuredHealthPercent: Float? = if (
            fullChargeCapacityMah != null &&
            designCapacityMah != null &&
            designCapacityMah > 0
        ) {
            ((fullChargeCapacityMah.toFloat() / designCapacityMah.toFloat()) * 100f).coerceIn(10f, 110f)
        } else {
            null
        }

        val degradationSummary = if (measuredHealthPercent != null) {
            String.format(
                Locale.US,
                "Measured Full Capacity (%d mAh) vs Factory Design (%d mAh) indicates ~%.1f%% capacity retention.",
                fullChargeCapacityMah,
                designCapacityMah,
                measuredHealthPercent
            )
        } else {
            "Numeric degradation % is N/A because the device kernel does not expose both hardware Full Charge Capacity (charge_full) and Design Capacity. Battery Boss never invents fake health percentages."
        }

        val tempStatus = when {
            temperatureCelsius == null -> "N/A (Temperature sensor unavailable)"
            temperatureCelsius >= 43f -> String.format(Locale.US, "%.1f °C • High Thermal Stress (Slow down charging)", temperatureCelsius)
            temperatureCelsius >= 38f -> String.format(Locale.US, "%.1f °C • Warm (Normal during fast charging)", temperatureCelsius)
            temperatureCelsius < 10f -> String.format(Locale.US, "%.1f °C • Cold (Increased internal resistance)", temperatureCelsius)
            else -> String.format(Locale.US, "%.1f °C • Optimal Thermal Range (15°C–37°C)", temperatureCelsius)
        }

        return BatteryHealthReport(
            reportedOsHealthStatus = healthLabel,
            osHealthExplanation = osExplanation,
            cycleCount = cycleCount,
            cycleCountSource = cycleCountSource,
            designCapacityMah = designCapacityMah,
            designCapacitySource = designCapSource,
            fullChargeCapacityMah = fullChargeCapacityMah,
            fullChargeCapacitySource = fullCapSource,
            remainingCapacityMah = chargeCounterMah,
            remainingCapacitySource = if (chargeCounterMah != null) {
                "BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER"
            } else {
                "N/A — Fuel gauge coulomb counter not exposed"
            },
            energyCounterWh = energyCounterWh,
            measuredCapacityHealthPercent = measuredHealthPercent,
            degradationSummary = degradationSummary,
            temperatureStatus = tempStatus
        )
    }

    private fun buildDiagnosticMetricsList(
        percentage: Int,
        statusLabel: String,
        healthLabel: String,
        voltageVolts: Float?,
        voltageMillivolts: Int?,
        currentMilliAmps: Int?,
        averageCurrentMilliAmps: Int?,
        powerWatts: Float?,
        temperatureCelsius: Float?,
        technology: String?,
        chargingSource: ChargingSource,
        chargeCounterMah: Int?,
        cycleCount: Int?,
        cycleCountSource: String,
        designCapacityMah: Int?,
        designCapSource: String,
        fullChargeCapacityMah: Int?,
        fullCapSource: String,
        energyCounterWh: Float?,
        estimatedMinutesToFull: Int?,
        chargingSpeedTier: ChargingSpeedTier
    ): List<DiagnosticMetric> = listOf(
        DiagnosticMetric(
            title = "Battery Percentage",
            value = "$percentage%",
            sourceApi = "EXTRA_LEVEL / EXTRA_SCALE",
            confidence = MetricConfidence.HIGH_DIRECT,
            explanation = "Direct state-of-charge ratio reported by the fuel gauge IC."
        ),
        DiagnosticMetric(
            title = "Battery Charging Status",
            value = statusLabel,
            sourceApi = "BatteryManager.EXTRA_STATUS",
            confidence = MetricConfidence.HIGH_DIRECT,
            explanation = "Current PMIC charging state machine status."
        ),
        DiagnosticMetric(
            title = "Battery Health Condition",
            value = healthLabel,
            sourceApi = "BatteryManager.EXTRA_HEALTH",
            confidence = MetricConfidence.QUALITATIVE_OS,
            explanation = "Qualitative safety status flag (Good / Overheat / Over-voltage). Not a numeric wear %."
        ),
        DiagnosticMetric(
            title = "Battery Voltage",
            value = voltageVolts?.let { String.format(Locale.US, "%.3f V (%d mV)", it, voltageMillivolts ?: 0) } ?: "N/A",
            sourceApi = "BatteryManager.EXTRA_VOLTAGE",
            confidence = if (voltageVolts != null) MetricConfidence.HIGH_DIRECT else MetricConfidence.UNAVAILABLE,
            explanation = if (voltageVolts != null) {
                "Terminal voltage measured across the lithium cell pack."
            } else {
                "N/A — Kernel did not include EXTRA_VOLTAGE in battery broadcast."
            }
        ),
        DiagnosticMetric(
            title = "Instant Battery Current",
            value = currentMilliAmps?.let { "$it mA" } ?: "N/A",
            sourceApi = "BATTERY_PROPERTY_CURRENT_NOW",
            confidence = if (currentMilliAmps != null) MetricConfidence.HIGH_DIRECT else MetricConfidence.UNAVAILABLE,
            explanation = if (currentMilliAmps != null) {
                "Instantaneous net current entering or leaving the battery cell (normalized from µA/mA)."
            } else {
                "N/A — This device kernel or emulator does not expose BATTERY_PROPERTY_CURRENT_NOW."
            }
        ),
        DiagnosticMetric(
            title = "Average Battery Current",
            value = averageCurrentMilliAmps?.let { "$it mA" } ?: "N/A",
            sourceApi = "BATTERY_PROPERTY_CURRENT_AVERAGE",
            confidence = if (averageCurrentMilliAmps != null) MetricConfidence.HIGH_DIRECT else MetricConfidence.UNAVAILABLE,
            explanation = if (averageCurrentMilliAmps != null) {
                "Rolling average current computed by the hardware fuel gauge."
            } else {
                "N/A — Many OEM fuel gauges omit CURRENT_AVERAGE and only report CURRENT_NOW."
            }
        ),
        DiagnosticMetric(
            title = "Measured Battery-Side Power",
            value = powerWatts?.let { String.format(Locale.US, "%.2f W", abs(it)) } ?: "N/A",
            sourceApi = "Derived: EXTRA_VOLTAGE × CURRENT_NOW",
            confidence = if (powerWatts != null) MetricConfidence.MEDIUM_DERIVED else MetricConfidence.UNAVAILABLE,
            explanation = if (powerWatts != null) {
                "Net power entering/leaving the battery cell (V_cell × I_cell). Note: Wall brick input power is higher due to system load and DC-DC conversion loss."
            } else {
                "N/A — Requires both hardware Voltage (V) and Instant Current (mA) sensors."
            }
        ),
        DiagnosticMetric(
            title = "Charging Speed Analysis",
            value = chargingSpeedTier.label,
            sourceApi = "Derived from Battery-Side Wattage",
            confidence = if (powerWatts != null) MetricConfidence.MEDIUM_DERIVED else MetricConfidence.UNAVAILABLE,
            explanation = chargingSpeedTier.description
        ),
        DiagnosticMetric(
            title = "Battery Temperature",
            value = temperatureCelsius?.let { String.format(Locale.US, "%.1f °C (%.1f °F)", it, it * 9f / 5f + 32f) } ?: "N/A",
            sourceApi = "BatteryManager.EXTRA_TEMPERATURE",
            confidence = if (temperatureCelsius != null) MetricConfidence.HIGH_DIRECT else MetricConfidence.UNAVAILABLE,
            explanation = if (temperatureCelsius != null) {
                "NTC thermistor reading inside the battery pack (reported in 0.1°C steps)."
            } else {
                "N/A — Battery thermistor reading not reported by hardware."
            }
        ),
        DiagnosticMetric(
            title = "Battery Technology",
            value = technology ?: "N/A",
            sourceApi = "BatteryManager.EXTRA_TECHNOLOGY",
            confidence = if (technology != null) MetricConfidence.HIGH_DIRECT else MetricConfidence.UNAVAILABLE,
            explanation = if (technology != null) "Cell chemistry identifier reported by kernel driver." else "N/A — Chemistry string omitted by kernel."
        ),
        DiagnosticMetric(
            title = "Charging Source",
            value = chargingSource.displayName,
            sourceApi = "BatteryManager.EXTRA_PLUGGED",
            confidence = MetricConfidence.HIGH_DIRECT,
            explanation = "Active power rail negotiated by the PMIC (AC, USB, Wireless, Dock, or Battery)."
        ),
        DiagnosticMetric(
            title = "Remaining Capacity (Charge Counter)",
            value = chargeCounterMah?.let { "$it mAh" } ?: "N/A",
            sourceApi = "BATTERY_PROPERTY_CHARGE_COUNTER",
            confidence = if (chargeCounterMah != null) MetricConfidence.HIGH_DIRECT else MetricConfidence.UNAVAILABLE,
            explanation = if (chargeCounterMah != null) {
                "Remaining charge in milliampere-hours (converted from kernel µAh coulomb counter)."
            } else {
                "N/A — Hardware coulomb counter is not exposed on this device."
            }
        ),
        DiagnosticMetric(
            title = "Battery Cycle Count",
            value = cycleCount?.let { "$it cycles" } ?: "N/A",
            sourceApi = cycleCountSource,
            confidence = if (cycleCount != null) MetricConfidence.HIGH_DIRECT else MetricConfidence.UNAVAILABLE,
            explanation = if (cycleCount != null) {
                "Total full-equivalent charge-discharge cycles recorded by the fuel gauge."
            } else {
                "N/A — Cycle count requires Android 14+ EXTRA_CYCLE_COUNT support or unrestricted kernel sysfs access."
            }
        ),
        DiagnosticMetric(
            title = "Design Capacity (Factory)",
            value = designCapacityMah?.let { "$it mAh" } ?: "N/A",
            sourceApi = designCapSource,
            confidence = if (designCapacityMah != null) MetricConfidence.HIGH_DIRECT else MetricConfidence.UNAVAILABLE,
            explanation = if (designCapacityMah != null) {
                "Nominal rated battery capacity specified by the device manufacturer."
            } else {
                "N/A — Factory design capacity register is restricted on this build."
            }
        ),
        DiagnosticMetric(
            title = "Full Charge Capacity",
            value = fullChargeCapacityMah?.let { "$it mAh" } ?: "N/A",
            sourceApi = fullCapSource,
            confidence = if (fullChargeCapacityMah != null) MetricConfidence.MEDIUM_DERIVED else MetricConfidence.UNAVAILABLE,
            explanation = if (fullChargeCapacityMah != null) {
                "Last measured capacity at 100% full charge."
            } else {
                "N/A — Kernel blocks charge_full sysfs node; charge to 100% while monitoring to capture."
            }
        ),
        DiagnosticMetric(
            title = "Energy Counter",
            value = energyCounterWh?.let { String.format(Locale.US, "%.2f Wh", it) } ?: "N/A",
            sourceApi = "BATTERY_PROPERTY_ENERGY_COUNTER",
            confidence = if (energyCounterWh != null) MetricConfidence.HIGH_DIRECT else MetricConfidence.UNAVAILABLE,
            explanation = if (energyCounterWh != null) {
                "Remaining energy in watt-hours reported by the hardware fuel gauge."
            } else {
                "N/A — Most smartphone PMICs report coulomb charge (mAh) rather than energy (nWh)."
            }
        ),
        DiagnosticMetric(
            title = "Estimated Time to Full",
            value = estimatedMinutesToFull?.let { "$it min" } ?: "N/A",
            sourceApi = "computeChargeTimeRemaining() / Coulomb Delta",
            confidence = if (estimatedMinutesToFull != null) MetricConfidence.MEDIUM_DERIVED else MetricConfidence.UNAVAILABLE,
            explanation = if (estimatedMinutesToFull != null) {
                "Estimated minutes remaining until 100% full charge (includes CC/CV taper allowance)."
            } else {
                "N/A — Only available while actively charging with a valid positive current sensor."
            }
        )
    )

    private fun readSysfsInt(path: String): Int? {
        return runCatching {
            val file = File(path)
            if (file.exists() && file.canRead()) {
                file.readText().trim().toIntOrNull()
            } else {
                null
            }
        }.getOrNull()
    }

    private fun mapChargingSource(plugged: Int): ChargingSource = when (plugged) {
        BatteryManager.BATTERY_PLUGGED_AC -> ChargingSource.AC
        BatteryManager.BATTERY_PLUGGED_USB -> ChargingSource.USB
        BatteryManager.BATTERY_PLUGGED_WIRELESS -> ChargingSource.WIRELESS
        BatteryManager.BATTERY_PLUGGED_DOCK -> ChargingSource.DOCK
        else -> ChargingSource.NONE
    }

    private fun mapHealthLabel(health: Int): String = when (health) {
        BatteryManager.BATTERY_HEALTH_GOOD -> "Good"
        BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheating"
        BatteryManager.BATTERY_HEALTH_DEAD -> "Dead / Depleted"
        BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Over Voltage"
        BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "Hardware Failure"
        BatteryManager.BATTERY_HEALTH_COLD -> "Too Cold"
        else -> "N/A"
    }

    fun getDetectedOemGuide(): OemOptimizationGuide {
        val manufacturer = (Build.MANUFACTURER ?: "Android").trim()
        val brand = (Build.BRAND ?: manufacturer).trim()
        val combined = "$manufacturer $brand".lowercase()

        return when {
            combined.contains("realme") || combined.contains("oppo") -> OemOptimizationGuide(
                manufacturerName = "$manufacturer ($brand)",
                osSkinName = "Realme UI / ColorOS",
                steps = listOf(
                    "Open App Info → Battery Usage → Enable 'Allow background activity' and 'Allow auto-launch'.",
                    "Open Recent Apps tray → Tap the 3-dot menu on Battery Boss → Select 'Lock' so system memory cleanup does not kill the voice service.",
                    "In System Settings → Battery → More Battery Settings → Optimize battery use → Set Battery Boss to 'Don't optimize'."
                ),
                caveatNote = "Realme UI and ColorOS aggressively suspend background services when the screen is off for extended periods unless Auto-Launch and Background Activity are explicitly allowed."
            )
            combined.contains("xiaomi") || combined.contains("redmi") || combined.contains("poco") -> OemOptimizationGuide(
                manufacturerName = "$manufacturer ($brand)",
                osSkinName = "Xiaomi HyperOS / MIUI",
                steps = listOf(
                    "Open App Info → Enable 'Autostart' (Background Autostart) toggle.",
                    "Tap 'Battery saver' (or Battery) → Choose 'No restrictions'.",
                    "In the Recent Apps screen, long-press or pull down on Battery Boss and tap the Padlock icon to lock it in RAM."
                ),
                caveatNote = "Xiaomi HyperOS and MIUI require the 'Autostart' permission for Battery Boss to resume automatically after a phone reboot."
            )
            combined.contains("samsung") -> OemOptimizationGuide(
                manufacturerName = "Samsung",
                osSkinName = "One UI",
                steps = listOf(
                    "Open App Info → Battery → Select 'Unrestricted'.",
                    "Open Device Care → Battery → Background usage limits → Ensure Battery Boss is added to 'Never auto sleeping apps'.",
                    "Keep the persistent Foreground Notification enabled so One UI recognizes active battery monitoring."
                ),
                caveatNote = "Samsung One UI places apps into Deep Sleep after a few days of inactivity unless set to 'Unrestricted' and added to 'Never sleeping apps'."
            )
            combined.contains("vivo") || combined.contains("iqoo") -> OemOptimizationGuide(
                manufacturerName = "$manufacturer ($brand)",
                osSkinName = "Funtouch OS / OriginOS",
                steps = listOf(
                    "Open Settings → Battery → Background power consumption management → Set Battery Boss to 'High background power usage' / 'Don't restrict'.",
                    "Open iManager → App Manager → Autostart Manager → Enable Autostart for Battery Boss.",
                    "Lock Battery Boss in the Recent Apps task switcher."
                ),
                caveatNote = "Vivo Funtouch OS blocks boot broadcasts and background TTS audio unless both Autostart and High Background Power Usage are enabled."
            )
            combined.contains("oneplus") -> OemOptimizationGuide(
                manufacturerName = "OnePlus",
                osSkinName = "OxygenOS",
                steps = listOf(
                    "Open App Info → Battery usage → Enable 'Allow background activity' and 'Allow auto-launch'.",
                    "Go to Settings → Battery → Advanced settings → Disable 'Sleep standby optimization' if overnight charging alerts are delayed.",
                    "Lock Battery Boss in Recent Apps."
                ),
                caveatNote = "OxygenOS shares power management with ColorOS; enabling Auto-launch ensures charger connection broadcasts wake the voice assistant."
            )
            combined.contains("motorola") || combined.contains("moto") -> OemOptimizationGuide(
                manufacturerName = "Motorola",
                osSkinName = "My UX / Hello UI",
                steps = listOf(
                    "Open App Info → App battery usage → Select 'Unrestricted'.",
                    "Ensure Adaptive Battery does not restrict Battery Boss when the screen is off."
                ),
                caveatNote = "Motorola runs near-stock Android and works reliably with the Foreground Service once battery usage is set to Unrestricted."
            )
            else -> OemOptimizationGuide(
                manufacturerName = manufacturer.replaceFirstChar { it.uppercase() },
                osSkinName = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                steps = listOf(
                    "Tap 'Optimization List' below and set Battery Boss to 'Unrestricted' or 'Don't optimize'.",
                    "Keep the Foreground Service notification active so Android prioritizes real-time charger and percentage broadcasts.",
                    "Allow Notification permission on Android 13+ so the persistent monitoring bar stays visible."
                ),
                caveatNote = "Android background execution varies by OEM firmware. Battery Boss uses a standard Foreground Service and dynamic broadcast registration, but strict OEM power savers can still pause background processes if optimization is left on."
            )
        }
    }
}
