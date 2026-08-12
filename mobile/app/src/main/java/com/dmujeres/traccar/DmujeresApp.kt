package com.dmujeres.traccar

import android.app.Application
import com.dmujeres.traccar.db.AppDatabase

class DmujeresApp : Application() {
    val database: AppDatabase by lazy { AppDatabase.getInstance(this) }
}
