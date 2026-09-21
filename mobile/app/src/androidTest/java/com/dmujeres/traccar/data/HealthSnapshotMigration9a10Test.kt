package com.dmujeres.traccar.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F0: Room 9→10 instrumentado. Crea la BD v9 EXACTA (health_snapshots con
 * `uploadedAt`/`healthState`, sin `funnel`), abre con Room + MIGRATION_9_10 y
 * valida: migración, datos que sobreviven y default honesto (`funnel=''` =
 * "sin dato del bucket").
 */
class HealthSnapshotMigration9a10Test {

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
            "CREATE TABLE IF NOT EXISTS `sequence_state` (`id` INTEGER NOT NULL, " +
                "`sequence` INTEGER NOT NULL, PRIMARY KEY(`id`))"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `dead_letters` (`messageId` TEXT NOT NULL, " +
                "`deviceId` TEXT NOT NULL, `sequence` INTEGER NOT NULL, `reason` TEXT NOT NULL, " +
                "`movedAt` INTEGER NOT NULL, PRIMARY KEY(`messageId`))"
        )
    }

    /** health_snapshots EXACTA en v9: con uploadedAt y healthState, sin funnel. */
    private fun createHealthSnapshotsV9(db: SQLiteDatabase) {
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
                "`outbox` INTEGER NOT NULL, " +
                "`payloadVersion` INTEGER NOT NULL DEFAULT 1, " +
                "`healthState` TEXT NOT NULL DEFAULT 'UNKNOWN', " +
                "`uploadedAt` INTEGER NOT NULL DEFAULT 0)"
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
    fun migracion9a10AgregaFunnelConDefaultHonesto() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbFile = context.getDatabasePath("migration-9-10-test.db")
        dbFile.parentFile?.mkdirs()
        dbFile.delete()

        val legacy = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        legacy.execSQL("PRAGMA user_version = 9")
        createCoreTables(legacy)
        createHealthSnapshotsV9(legacy)
        legacy.execSQL(
            "INSERT INTO health_snapshots " +
                "(sessionId, deviceId, wallMs, wallBucket, elapsedMs, eventType, reason, fgs, motion, network, outbox) " +
                "VALUES ('s1', 'd1', 1000, 1, 500, 'HEARTBEAT', '', 1, 'STATIONARY', 'wifi', 2)"
        )
        legacy.close()

        val room = Room.databaseBuilder(context, AppDatabase::class.java, "migration-9-10-test.db")
            .addMigrations(AppDatabase.MIGRATION_9_10)
            .allowMainThreadQueries()
            .build()
        try {
            val cursor = room.openHelper.readableDatabase.query("SELECT funnel FROM health_snapshots")
            assertTrue("la fila migrada debe existir", cursor.moveToFirst())
            assertEquals("'' = sin dato del bucket (no inventado)", "", cursor.getString(0))
            cursor.close()
        } finally {
            room.close()
            dbFile.delete()
        }
    }
}
