package com.dmujeres.traccar.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Room 7→8 en dispositivo real (instrumented): crea una BD v7 con SQLite puro
 * (esquema real de pending_positions/sequence_states/dead_letters), abre con
 * Room + MIGRATION_7_8 y valida: migración, datos que sobreviven y runtime
 * Room (insert/read/idempotencia/retención) sobre la MISMA BD del watchdog.
 */
class HealthSnapshotMigrationTest {

    /** Crea la BD v7 EXACTA (sin health_snapshots) con SQL puro. */
    private fun createV7(db: SQLiteDatabase) {
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
            "CREATE INDEX IF NOT EXISTS `index_pending_positions_retryAt` " +
                "ON `pending_positions` (`retryAt`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_pending_positions_isControl` " +
                "ON `pending_positions` (`isControl`)"
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
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_dead_letters_sequence` ON `dead_letters` (`sequence`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_dead_letters_reason` ON `dead_letters` (`reason`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_dead_letters_quarantinedAt` " +
                "ON `dead_letters` (`quarantinedAt`)"
        )
        // Identidad de Room v7 para que la apertura v8 ejecute MIGRATION_7_8
        // (la identidad v8 se reescribe después de migrar).
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER NOT NULL, " +
                "identity_hash TEXT NOT NULL, PRIMARY KEY(id))"
        )
        db.execSQL(
            "INSERT INTO room_master_table (id, identity_hash) VALUES (1, 'idempotencia-v7-data-preservada')"
        )
        // Dato preexistente que DEBE sobrevivir a la migración.
        db.execSQL(
            "INSERT INTO pending_positions (messageId, deviceId, sequence, payload, observedAt, attempts) " +
                "VALUES ('m1', 'd', 1, '{}', '2026-01-01T00:00:00Z', 0)"
        )
    }

    @Test
    fun migrate7To8DatosSobrevivenYTablaNuevaExiste() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "migration-f0-test.db"
        context.deleteDatabase(dbName)
        SQLiteDatabase.openOrCreateDatabase(
            context.getDatabasePath(dbName), null
        ).use { raw ->
            createV7(raw)
            raw.close()
        }
        // Abrir con Room: Room ve version 7 (por PRAGMA user_version=0? no —
        // creamos la BD sin version SQLite: setVersion)
        val raw = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(dbName), null)
        raw.version = 7 // versión PRAGMA de la BD: 7
        raw.close()

        val db = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            // R3.5: la BD destino es v9 — registrar AMBAS migraciones o Room
            // falla al buscar 8→9 tras el 7→8.
            .addMigrations(AppDatabase.MIGRATION_7_8, AppDatabase.MIGRATION_8_9)
            .allowMainThreadQueries()
            .build()
        runBlocking {
            // dato viejo sobrevive
            assertEquals(1, db.positionDao().count())
            // tabla nueva existe y está vacía
            assertEquals(null, db.healthSnapshotDao().last())
            // runtime: insert/read
            db.healthSnapshotDao().insert(
                HealthSnapshot(
                    sessionId = "mig", deviceId = "d", wallMs = 1000, wallBucket = 1,
                    elapsedMs = 500, eventType = "HEARTBEAT", reason = "", fgs = true,
                    motion = "MOVING", network = "wifi", outbox = 0,
                ),
            )
            val snap = db.healthSnapshotDao().last()
            assertTrue(snap != null && snap.sessionId == "mig")
            // idempotencia: mismo (sessionId, wallBucket) NO duplica
            db.healthSnapshotDao().insert(
                HealthSnapshot(
                    sessionId = "mig", deviceId = "d", wallMs = 1000, wallBucket = 1,
                    elapsedMs = 999, eventType = "HEARTBEAT", reason = "", fgs = true,
                    motion = "MOVING", network = "wifi", outbox = 0,
                ),
            )
            val all = runBlocking { db.healthSnapshotDao().bySession("mig") }
            assertEquals(1, all.size)
            assertEquals(500L, all[0].elapsedMs)
        }
        db.close()
    }

    @Test
    fun runtimeRoomWriteReadIdempotenciaRetencion() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = AppDatabase.getInstance(context)
        val dao = db.healthSnapshotDao()
        val sessionId = "test-session-f0"
        val bucket = System.currentTimeMillis() / 60_000L

        val s1 = HealthSnapshot(
            sessionId = sessionId, deviceId = "qa", wallMs = bucket * 60_000L,
            wallBucket = bucket, elapsedMs = 500L, eventType = "HEARTBEAT",
            reason = "", fgs = true, motion = "STATIONARY", network = "wifi",
            outbox = 0,
        )
        runBlocking {
            dao.insert(s1)
            dao.insert(s1.copy(elapsedMs = 999L)) // mismo bucket: NO duplica
        }
        val all = runBlocking { dao.bySession(sessionId) }
        assertEquals(1, all.size)
        assertEquals(500L, all[0].elapsedMs)
        val last = runBlocking { dao.last() }
        assertTrue(last != null && last.sessionId == sessionId)
        runBlocking { dao.deleteBefore(System.currentTimeMillis() + 60_000L) }
        assertEquals(null, runBlocking { dao.last() })
    }

    /** Crea la BD v8 EXACTA (health_snapshots sin uploadedAt/healthState). */
    private fun createV8(db: SQLiteDatabase) {
        createV7(db)
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `health_snapshots` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`sessionId` TEXT NOT NULL, `deviceId` TEXT NOT NULL, " +
                "`wallMs` INTEGER NOT NULL DEFAULT 0, `wallBucket` INTEGER NOT NULL DEFAULT 0, " +
                "`elapsedMs` INTEGER NOT NULL DEFAULT 0, `eventType` TEXT NOT NULL, " +
                "`reason` TEXT NOT NULL, `fgs` INTEGER NOT NULL DEFAULT 0, " +
                "`motion` TEXT NOT NULL, `network` TEXT NOT NULL, " +
                "`outbox` INTEGER NOT NULL DEFAULT 0, `payloadVersion` INTEGER NOT NULL DEFAULT 1)"
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_health_snapshots_sessionId_wallBucket` " +
                "ON `health_snapshots` (`sessionId`, `wallBucket`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_health_snapshots_wallMs` " +
                "ON `health_snapshots` (`wallMs`)"
        )
        db.execSQL(
            "INSERT INTO health_snapshots (sessionId, deviceId, wallMs, wallBucket, elapsedMs, " +
                "eventType, reason, fgs, motion, network, outbox, payloadVersion) " +
                "VALUES ('s-v8', 'd', 500, 8, 10, 'HEARTBEAT', '', 1, 'STATIONARY', 'wifi', 1, 1)"
        )
    }

    @Test
    fun migrate8To9SaludMantieneDatosYDefaultPendiente() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "migration-f7-test.db"
        context.deleteDatabase(dbName)
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(dbName), null).use { raw ->
            createV8(raw)
            raw.close()
        }
        val raw = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(dbName), null)
        raw.version = 8
        raw.close()

        val db = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(AppDatabase.MIGRATION_8_9)
            .allowMainThreadQueries()
            .build()
        runBlocking {
            // El snapshot preexistente sobrevive con defaults honestos.
            val all = db.healthSnapshotDao().bySession("s-v8")
            assertEquals(1, all.size)
            assertEquals(0L, all[0].uploadedAt)
            assertEquals("UNKNOWN", all[0].healthState)
            // Los pendientes incluyen el snapshot migrado (se subirá al servidor).
            assertEquals(1, db.healthSnapshotDao().unsent(10).size)
            // markUploaded lo saca de pendientes sin borrarlo.
            db.healthSnapshotDao().markUploaded(listOf(all[0].id), 999L)
            assertEquals(0, db.healthSnapshotDao().unsent(10).size)
            assertEquals(1, db.healthSnapshotDao().bySession("s-v8").size)
        }
        db.close()
    }
}
