package com.dmujeres.gps.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.dmujeres.gps.data.PreferencesManager
import com.dmujeres.gps.service.GpsTrackingService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Reinicia el rastreo tras reinicio del dispositivo si estaba activado. */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED &&
            intent?.action != "android.intent.action.QUICKBOOT_POWERON"
        ) {
            return
        }

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val prefs = PreferencesManager(context).preferences.first()
                if (prefs.trackingEnabled) {
                    GpsTrackingService.start(context)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
