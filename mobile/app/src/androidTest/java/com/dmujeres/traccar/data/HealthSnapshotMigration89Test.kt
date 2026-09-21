package com.dmujeres.traccar.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R3.5-15: Room 8→9 instrumentado. Crea la BD v8 EXACTA (health_snapshots sin
 * `uploadedAt`/`healthState`, según MIGRATION_7_8), abre con Room + MIGRATION_8_9
 * y valida: migración, datos que sobreviven, defaults honestos (uploadedAt=0,
 * healthState='UNKNOWN' = "no subido") y runtime Room sobre la misma BD.
 * Nunca destructiva: los snapshots pendientes se suben en el próximo ciclo.
 */
class HealthSnapshotMigration89Test {

    /** Tablas base idénticas a v7/v8 (no cambian en 8→9). */
    private fun createCoreTables(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `pending_positions` (`messageId` TEXT NOT NULL, " +
                "`deviceId` TEXT NOT NULL, `sequence` INTEGER NOT NULL, `payload` TEXT NOT NULL, " +
                "`observedAt` TEXT NOT NULL, `enqueuedAt` INTEGER NOT NULL DEFAULT 0, " +
                "`isControl` INTEGER NOT NULL DEFAULT 0, `journeyId` INTEGER NOT NULL DEFAULT 0, " +
                "`attempts` INTEGER NOT NULL, `retryAt` INTEGER NOT NULL DEFAULT 0, " +
                "PRIMARY KEY(`messageId`))"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_pending_positions_sequence` " +
                "ON `pending_positions` (`sequence`)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `sequence_state` (`id` INTEGER NOT NULL, " +
                "`sequence` INTEGER NOT NULL, PRIMARY KEY(`id`))"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `dead_letters` (`messageId` TEXT NOT NULL, " +
                "`deviceId` TEXT NOT NULL, `sequence` INTEGER NOT NULL, `reason` TEXT NOT NULL, " +
                "`observedAt` TEXT NOT NULL, `enqueuedAt` INTEGER NOT NULL DEFAULT 0, " +
                "`attempts` INTEGER NOT NULL, `payloadBytes` INTEGER NOT NULL, " +
                "`quarantinedAt` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`messageId`))"
        )
        // Identidad Room (Room la reescribe tras migrar).
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER NOT NULL, " +
                "identity_hash TEXT NOT NULL, PRIMARY KEY(id))"
        )
        db.execSQL(
            "INSERT INTO room_master_table (id, identity_hash) VALUES (1, 'idempotencia-v8-estado-salud')"
        )
    }

    /** health_snapshots EXACTA en v8: SIN uploadedAt ni healthState. */
    private fun createHealthSnapshotsV8(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `health_snapshots` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`sessionId` TEXT NOT NULL, " +
                "`deviceId` TEXT NOT NULL, " +
                "`wallMs` INTEGER NOT NULL DEFAULT 0, " +
                "`wallBucket` INTEGER NOT NULL DEFAULT 0, " +
                "`elapsedMs` INTEGER NOT NULL DEFAULT 0, " +
                "`eventType` TEXT NOT NULL, " +
                "`reason` TEXT NOT NULL, " +
                "`fgs` INTEGER NOT NULL DEFAULT 0, " +
                "`motion` TEXT NOT NULL, " +
                "`network` TEXT NOT NULL, " +
                "`outbox` INTEGER NOT NULL DEFAULT 0, " +
                "`payloadVersion` INTEGER NOT NULL DEFAULT 1)"
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_health_snapshots_sessionId_wallBucket` " +
                "ON `health_snapshots` (`sessionId`, `wallBucket`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_health_snapshots_wallMs` " +
                "ON `health_snapshots` (`wallMs`)"
        )
    }

    @Test
    fun migrate8To9DatosSobrevivenYDefaultsHonestos() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "migration-r35-test.db"
        context.deleteDatabase(dbName)
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(dbName), null).use { raw ->
            createCoreTables(raw)
            createHealthSnapshotsV8(raw)
            // Snapshot preexistente (pendiente de subida) que DEBE sobrevivir.
            raw.execSQL(
                "INSERT INTO health_snapshots (sessionId, deviceId, wallMs, wallBucket, " +
                    "elapsedMs, eventType, reason, fgs, motion, network, outbox, payloadVersion) " +
                    "VALUES ('mig', 'd', 1000, 1, 500, 'HEARTBEAT', '', 1, 'MOVING', 'wifi', 0, 1)"
            )
            // Dato outbox preexistente que DEBE sobrevivir.
            raw.execSQL(
                "INSERT INTO pending_positions (messageId, deviceId, sequence, payload, " +
                    "observedAt, attempts) VALUES ('m1', 'd', 1, '{}', '2026-01-01T00:00:00Z', 0)"
            )
            raw.close()
        }
        val raw = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(dbName), null)
        raw.version = 8
        raw.close()

        val db = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(AppDatabase.MIGRATION_8_9)
            .allowMainThreadQueries()
            .build()
        kotlinx.coroutines.runBlocking {
            // Datos que sobreviven: pending + snapshot v8 (idempotencia intacta).
            assertEquals(1, db.positionDao().count())
            val all = db.healthSnapshotDao().bySession("mig")
            assertEquals(1, all.size)
            assertEquals(500L, all[0].elapsedMs)
            // Defaults honestos: el snapshot quedó marcado como NO subido.
            assertEquals(0L, all[0].uploadedAt)
            assertEquals("UNKNOWN", all[0].healthState)
            // Runtime: insert con los campos nuevos (state real, no inventado).
            db.healthSnapshotDao().insert(
                HealthSnapshot(
                    sessionId = "mig", deviceId = "d", wallMs = 2000, wallBucket = 2,
                    elapsedMs = 800, eventType = "HEARTBEAT", reason = "", fgs = true,
                    motion = "MOVING", network = "wifi", outbox = 2,
                    uploadedAt = 0, healthState = "HEALTHY",
                ),
            )
            val allAfter = db.healthSnapshotDao().bySession("mig")
            assertEquals(2, allAfter.size)
            val fresh = allAfter.first { it.wallBucket == 2L }
            assertEquals("HEALTHY", fresh.healthState)
        }
        db.close()
    }
}
