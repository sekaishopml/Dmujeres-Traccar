package org.traccar.client

import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * FCM del cliente de respaldo (plan B).
 *
 * Reusa el canal de recuperación del servidor: el push es un data message
 * `type=TRACKING_RECOVERY_PROBE` con `recoveryAttemptId`. Al recibirlo se
 * enciende la captura (FGS + wake lock del cliente oficial) y se acusa al
 * servidor, que audita todo en tc_recovery_event.
 */
class DmujeresMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        DmujeresApi.registerFcmToken(this, token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val type = message.data["type"] ?: return
        if (type != "TRACKING_RECOVERY_PROBE") {
            Log.i(TAG, "Push ignorado: $type")
            return
        }
        val attemptId = message.data["recoveryAttemptId"].orEmpty()
        // La recuperación enciende la captura aunque el usuario la hubiera apagado:
        // es una orden operativa auditada, no una decisión del teléfono.
        PreferenceManager.getDefaultSharedPreferences(this)
            .edit().putBoolean(MainFragment.KEY_STATUS, true).apply()
        ContextCompat.startForegroundService(this, Intent(this, TrackingService::class.java))
        DmujeresApi.recoveryAck(this, attemptId, "WAKE")
        Log.i(TAG, "Recuperación FCM atendida (attempt=$attemptId)")
    }

    private companion object {
        const val TAG = "DmujeresFcm"
    }
}
