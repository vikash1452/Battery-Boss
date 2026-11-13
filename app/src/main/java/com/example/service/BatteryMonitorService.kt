package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.data.local.BatteryBossDatabase
import com.example.data.local.BatteryBossRepository
import com.example.data.preferences.SettingsDataStore
import com.example.hardware.BatteryHardwareReader
import com.example.model.BatteryHardwareSnapshot
import com.example.model.TelemetrySamplePoint
import com.example.voice.OfflineVoiceAssistant
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Shared singleton hub so UI and Foreground Service observe the exact same hardware state,
 * live telemetry graph buffer, voice engine status, and active service state.
 */
object BatteryTelemetryHub {
    private val _snapshot = MutableStateFlow(BatteryHardwareSnapshot())
    val snapshot: StateFlow<BatteryHardwareSnapshot> = _snapshot.asStateFlow()

    private val _telemetryHistory = MutableStateFlow<List<TelemetrySamplePoint>>(emptyList())
    val telemetryHistory: StateFlow<List<TelemetrySamplePoint>> = _telemetryHistory.asStateFlow()

    private val _isServiceRunning = MutableStateFlow(false)
    val isServiceRunning: StateFlow<Boolean> = _isServiceRunning.asStateFlow()

    @Volatile
    private var voiceAssistantInstance: OfflineVoiceAssistant? = null

    fun getOrCreateVoiceAssistant(context: Context): OfflineVoiceAssistant {
        return voiceAssistantInstance ?: synchronized(this) {
            voiceAssistantInstance ?: run {
                val appContext = context.applicationContext
                val dataStore = SettingsDataStore(appContext)
                val db = BatteryBossDatabase.getInstance(appContext)
                val repo = BatteryBossRepository(db.batteryBossDao())
                OfflineVoiceAssistant(appContext, dataStore, repo).also {
                    voiceAssistantInstance = it
                }
            }
        }
    }

    fun updateSnapshot(newSnapshot: BatteryHardwareSnapshot) {
        _snapshot.value = newSnapshot
        val point = TelemetrySamplePoint(
            timestampMillis = newSnapshot.timestampMillis,
            percentage = newSnapshot.percentage,
            powerWatts = newSnapshot.powerWatts?.let { abs(it) },
            currentMilliAmps = newSnapshot.currentMilliAmps,
            temperatureCelsius = newSnapshot.temperatureCelsius
        )
        val currentList = _telemetryHistory.value
        val shouldAppend = currentList.isEmpty() ||
            (newSnapshot.timestampMillis - currentList.last().timestampMillis >= 3_500L) ||
            (currentList.last().percentage != newSnapshot.percentage)

        if (shouldAppend) {
            _telemetryHistory.value = (currentList + point).takeLast(30)
        }
    }

    fun setServiceRunning(running: Boolean) {
        _isServiceRunning.value = running
    }
}

/**
 * Foreground Service that continuously monitors Android battery broadcasts
 * (ACTION_BATTERY_CHANGED, ACTION_POWER_CONNECTED, ACTION_POWER_DISCONNECTED)
 * even when the app is closed or the screen is off.
 */
class BatteryMonitorService : Service() {

    companion object {
        const val CHANNEL_ID = "battery_boss_monitor_channel"
        const val NOTIFICATION_ID = 4010
        const val ACTION_START_MONITORING = "com.example.action.START_MONITORING"
        const val ACTION_STOP_MONITORING = "com.example.action.STOP_MONITORING"
        const val ACTION_SPEAK_NOW = "com.example.action.SPEAK_NOW"
        const val ACTION_STOP_SPEAKING = "com.example.action.STOP_SPEAKING"
        const val EXTRA_TRIGGER_EVENT = "extra_trigger_event"

        fun startService(context: Context, triggerEvent: String? = null) {
            val intent = Intent(context, BatteryMonitorService::class.java).apply {
                action = ACTION_START_MONITORING
                if (triggerEvent != null) {
                    putExtra(EXTRA_TRIGGER_EVENT, triggerEvent)
                }
            }
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, BatteryMonitorService::class.java).apply {
                action = ACTION_STOP_MONITORING
            }
            runCatching {
                context.stopService(intent)
            }
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var hardwareReader: BatteryHardwareReader
    private lateinit var voiceAssistant: OfflineVoiceAssistant
    private lateinit var repository: BatteryBossRepository
    private lateinit var settingsDataStore: SettingsDataStore
    private var tickerJob: Job? = null
    private var isReceiverRegistered = false

    private val batteryBroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            when (action) {
                Intent.ACTION_BATTERY_CHANGED -> {
                    val snap = hardwareReader.readSnapshot(intent)
                    handleSnapshotUpdate(snap, explicitEvent = null)
                }
                Intent.ACTION_POWER_CONNECTED,
                Intent.ACTION_POWER_DISCONNECTED,
                Intent.ACTION_BATTERY_LOW,
                Intent.ACTION_BATTERY_OKAY -> {
                    val snap = hardwareReader.readSnapshot(null)
                    handleSnapshotUpdate(snap, explicitEvent = action)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        hardwareReader = BatteryHardwareReader(applicationContext)
        voiceAssistant = BatteryTelemetryHub.getOrCreateVoiceAssistant(applicationContext)
        val db = BatteryBossDatabase.getInstance(applicationContext)
        repository = BatteryBossRepository(db.batteryBossDao())
        settingsDataStore = SettingsDataStore(applicationContext)

        createNotificationChannel()
        val initialSnapshot = hardwareReader.readSnapshot()
        BatteryTelemetryHub.updateSnapshot(initialSnapshot)
        startForegroundCompat(buildNotification(initialSnapshot))
        BatteryTelemetryHub.setServiceRunning(true)

        registerBatteryReceiver()
        startLiveTelemetryTicker()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_MONITORING -> {
                BatteryTelemetryHub.setServiceRunning(false)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_SPEAK_NOW -> {
                val snap = hardwareReader.readSnapshot()
                BatteryTelemetryHub.updateSnapshot(snap)
                voiceAssistant.speakImmediateSample(snapshot = snap)
            }
            ACTION_STOP_SPEAKING -> {
                voiceAssistant.stopSpeaking()
            }
            else -> {
                val triggerEvent = intent?.getStringExtra(EXTRA_TRIGGER_EVENT)
                val snap = hardwareReader.readSnapshot()
                handleSnapshotUpdate(snap, explicitEvent = triggerEvent)
            }
        }
        return START_STICKY
    }

    private fun handleSnapshotUpdate(snapshot: BatteryHardwareSnapshot, explicitEvent: String?) {
        BatteryTelemetryHub.updateSnapshot(snapshot)
        voiceAssistant.evaluateSnapshotAndAnnounce(snapshot, explicitEvent)
        updateNotification(snapshot)

        serviceScope.launch(Dispatchers.IO) {
            if (snapshot.isCharging) {
                repository.startOrUpdateChargingSession(
                    percentage = snapshot.percentage,
                    sourceName = snapshot.chargingSource.displayName,
                    watts = snapshot.powerWatts,
                    currentMa = snapshot.currentMilliAmps,
                    tempCelsius = snapshot.temperatureCelsius
                )
            } else {
                repository.completeActiveChargingSession(
                    finalPercentage = snapshot.percentage,
                    tempCelsius = snapshot.temperatureCelsius
                )
            }
        }
    }

    private fun startLiveTelemetryTicker() {
        tickerJob?.cancel()
        tickerJob = serviceScope.launch {
            while (isActive) {
                delay(4_000L)
                val snap = hardwareReader.readSnapshot()
                handleSnapshotUpdate(snap, explicitEvent = null)
            }
        }
    }

    private fun registerBatteryReceiver() {
        if (isReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_BATTERY_LOW)
            addAction(Intent.ACTION_BATTERY_OKAY)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(batteryBroadcastReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(batteryBroadcastReceiver, filter)
        }
        isReceiverRegistered = true
    }

    private fun startForegroundCompat(notification: Notification) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }
    }

    private fun updateNotification(snapshot: BatteryHardwareSnapshot) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        runCatching {
            nm.notify(NOTIFICATION_ID, buildNotification(snapshot))
        }
    }

    private fun buildNotification(snapshot: BatteryHardwareSnapshot): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            100,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val speakIntent = Intent(this, BatteryMonitorService::class.java).apply {
            action = ACTION_SPEAK_NOW
        }
        val speakPendingIntent = PendingIntent.getService(
            this,
            101,
            speakIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopSpeakIntent = Intent(this, BatteryMonitorService::class.java).apply {
            action = ACTION_STOP_SPEAKING
        }
        val stopSpeakPendingIntent = PendingIntent.getService(
            this,
            102,
            stopSpeakIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = "${snapshot.percentage}% • ${snapshot.statusLabel}"
        val tempText = snapshot.temperatureCelsius?.let { String.format(Locale.US, "%.1f°C", it) } ?: "N/A"
        val voltageText = snapshot.voltageVolts?.let { String.format(Locale.US, "%.2fV", it) } ?: "N/A"
        val powerText = snapshot.powerWatts?.let { String.format(Locale.US, "%.1fW", abs(it)) }
            ?: snapshot.currentMilliAmps?.let { "${it}mA" }
            ?: "N/A"

        val body = "${snapshot.chargingSource.displayName} • $powerText • $voltageText • $tempText"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_charging)
            .setContentTitle(title)
            .setContentText(body)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(contentPendingIntent)
            .addAction(
                android.R.drawable.ic_btn_speak_now,
                "Speak Status",
                speakPendingIntent
            )
            .addAction(
                android.R.drawable.ic_media_pause,
                "Stop Voice",
                stopSpeakPendingIntent
            )
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_desc)
                setShowBadge(false)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        BatteryTelemetryHub.setServiceRunning(false)
        tickerJob?.cancel()
        if (isReceiverRegistered) {
            runCatching { unregisterReceiver(batteryBroadcastReceiver) }
            isReceiverRegistered = false
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
