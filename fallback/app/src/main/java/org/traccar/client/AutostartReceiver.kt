/*
 * Copyright 2013 - 2021 Anton Tananaev (anton@traccar.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.traccar.client

import android.content.Context
import android.content.Intent
import androidx.preference.PreferenceManager

class AutostartReceiver : WakefulBroadcastReceiver() {

    @Suppress("UnsafeProtectedBroadcastReceiver")
    override fun onReceive(context: Context, intent: Intent) {
        // La alarma de rescate se rearma en boot y en reemplazo del paquete
        // (Doze congela el Handler pero respeta setAndAllowWhileIdle).
        runCatching { org.traccar.client.recovery.DozeAlarmReceiver.schedule(context) }
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
        // Si había jornada abierta persistida, el próximo arranque entra en
        // RECOVERING y reconcilia con el servidor (no se asume continuidad).
        runCatching {
            val journeyOpen = sharedPreferences.getBoolean(DmujeresApi.KEY_JOURNEY_OPEN, false)
            if (journeyOpen) {
                DatabaseHelper(context).putMeta(DatabaseHelper.KEY_RECOVERY_PENDING, "1")
            }
        }
        // Se conserva el arranque existente: la flota depende de él y el
        // encargo solo pide AÑADIR el rearme (no cambiar el arranque aquí).
        // Con red: en Android 12+ el arranque en segundo plano puede ser
        // rechazado (ForegroundServiceStartNotAllowedException) y antes ese
        // throw tumbaba el receiver sin aviso ni reintento; ahora se registra
        // y la alarma (ya rearmada arriba) + la próxima apertura reintentan.
        // Riesgo OEM aceptado: si el fabricante niega el arranque en segundo
        // plano, solo la apertura manual levanta el tracking (documentado).
        if (sharedPreferences.getBoolean(Prefs.STATUS, false)) {
            runCatching {
                startWakefulForegroundService(context, Intent(context, TrackingService::class.java))
            }.onFailure {
                android.util.Log.w("AutostartReceiver", "arranque en boot rechazado, reintenta la alarma/apertura", it)
                runCatching {
                    StatusActivity.addMessage("Arranque en boot rechazado por el sistema")
                }
            }
        }
    }

}
