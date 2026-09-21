package com.dmujeres.traccar.platform

import android.content.Context
import android.location.LocationManager
import android.os.Build
import android.provider.Settings

/**
 * Sustituto moderno de Settings.Secure.LOCATION_MODE (deprecated).
 * Intenta LocationManager.isLocationEnabled() (API 28+) y, si falla
 * (API < 28 o excepción), cae al método viejo con try/catch.
 */
object LocationState {
    fun isEnabled(context: Context): Boolean {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
                return lm.isLocationEnabled
            }
        } catch (_: Exception) {
            // cae al método viejo
        }
        return try {
            Settings.Secure.getInt(context.contentResolver, Settings.Secure.LOCATION_MODE, 0) != 0
        } catch (_: Exception) {
            // Optimista: si no se puede leer, no bloquear onboarding/telemetría.
            true
        }
    }
}
