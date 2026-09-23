package org.traccar.client

import android.content.Context

/** Flavor `regular` sin Firebase: no hay token que comprobar. */
object FcmStatus {
    fun hasToken(context: Context): Boolean? = null
}
