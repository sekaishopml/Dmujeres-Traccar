package org.traccar.client

import android.content.Context

object StationaryFenceFactory {

    fun create(context: Context): StationaryFence = NoStationaryFence
}
