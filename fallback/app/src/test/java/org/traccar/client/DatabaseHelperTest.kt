package org.traccar.client

import android.location.Location
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Config(sdk = [Build.VERSION_CODES.P])
@RunWith(RobolectricTestRunner::class)
class DatabaseHelperTest {
    @Test
    fun test() {

        val databaseHelper = DatabaseHelper(ApplicationProvider.getApplicationContext())

        var position: Position? = Position("123456789012345", Location("gps"), BatteryStatus())

        Assert.assertNull(databaseHelper.selectPosition())

        databaseHelper.insertPosition(position!!)

        position = databaseHelper.selectPosition()

        Assert.assertNotNull(position)

        databaseHelper.deletePosition(position!!.id)

        Assert.assertNull(databaseHelper.selectPosition())

    }


    @Test
    fun `el bufer respeta el tope y descarta los mas viejos`() {
        val databaseHelper = DatabaseHelper(ApplicationProvider.getApplicationContext())
        val limit = 5000
        // Referencia real del primer insertado (Robolectric aplica un offset al Location.time).
        val referenceLocation = Location("gps")
        referenceLocation.time = 1_000L
        val firstInsertedTime = Position("123456789012345", referenceLocation, BatteryStatus()).time.time
        // Inserta el tope + 10 y verifica que solo quedan las más nuevas.
        for (index in 0 until limit + 10) {
            val location = Location("gps")
            location.time = 1_000L + index
            databaseHelper.insertPosition(Position("123456789012345", location, BatteryStatus()))
        }
        Assert.assertEquals(limit, databaseHelper.countPositions())
        // La primera posición conservada debe ser la número 10 (las 10 más viejas se descartaron).
        val oldest = databaseHelper.selectPosition()
        Assert.assertNotNull(oldest)
        Assert.assertEquals(
            "count=" + databaseHelper.countPositions() + " oldest=" + oldest!!.time.time,
            firstInsertedTime + 10,
            oldest!!.time.time,
        )
    }
}
