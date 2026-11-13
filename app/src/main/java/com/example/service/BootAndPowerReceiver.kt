package com.example.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.data.preferences.SettingsDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Restores background monitoring on device reboot (BOOT_COMPLETED) when permitted
 * by user settings, and wakes BatteryMonitorService on power connect/disconnect broadcasts.
 */
class BootAndPowerReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val pendingResult = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        scope.launch {
            try {
                val settings = SettingsDataStore(context.applicationContext).getSettingsSnapshot()
                when (action) {
                    Intent.ACTION_BOOT_COMPLETED,
                    "android.intent.action.QUICKBOOT_POWERON",
                    Intent.ACTION_MY_PACKAGE_REPLACED -> {
                        if (settings.startOnBootEnabled && settings.backgroundMonitoringEnabled) {
                            BatteryMonitorService.startService(context.applicationContext)
                        }
                    }
                    Intent.ACTION_POWER_CONNECTED,
                    Intent.ACTION_POWER_DISCONNECTED -> {
                        if (settings.backgroundMonitoringEnabled) {
                            BatteryMonitorService.startService(
                                context = context.applicationContext,
                                triggerEvent = action
                            )
                        }
                    }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
