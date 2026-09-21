package com.dmujeres.traccar.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [PendingPosition::class, SequenceState::class, DeadLetter::class, HealthSnapshot::class], version = 10, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {

    abstract fun positionDao(): PositionDao

    abstract fun healthSnapshotDao(): HealthSnapshotDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "dmj_tracking.db"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10).build().also { instance = it }
            }

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE pending_positions ADD COLUMN enqueuedAt INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE pending_positions ADD COLUMN isControl INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "ALTER TABLE pending_positions ADD COLUMN journeyId INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS sequence_state (id INTEGER NOT NULL PRIMARY KEY, sequence INTEGER NOT NULL)"
                )
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE pending_positions ADD COLUMN retryAt INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Índices para el dispatch paginado (allDue/dueControls ordenados por
                // sequence y esperas por MIN(retryAt), más filtro isControl).
                // CREATE INDEX es idempotente con IF NOT EXISTS y no reescribe la tabla.
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_pending_positions_sequence` ON `pending_positions` (`sequence`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_pending_positions_retryAt` ON `pending_positions` (`retryAt`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_pending_positions_isControl` ON `pending_positions` (`isControl`)"
                )
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Cuarentena de NACK terminales: solo CREATE TABLE + índices
                // (sin reescritura, sin pérdida: el outbox no se toca).
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `dead_letters` (`messageId` TEXT NOT NULL, "
                        + "`deviceId` TEXT NOT NULL, `sequence` INTEGER NOT NULL, "
                        + "`reason` TEXT NOT NULL, `observedAt` TEXT NOT NULL, "
                        + "`enqueuedAt` INTEGER NOT NULL DEFAULT 0, `attempts` INTEGER NOT NULL, "
                        + "`payloadBytes` INTEGER NOT NULL, `quarantinedAt` INTEGER NOT NULL DEFAULT 0, "
                        + "PRIMARY KEY(`messageId`))"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_dead_letters_sequence` ON `dead_letters` (`sequence`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_dead_letters_reason` ON `dead_letters` (`reason`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_dead_letters_quarantinedAt` ON `dead_letters` (`quarantinedAt`)"
                )
            }
        }

        // Room 7→8: tabla health_snapshots (heartbeat local del proceso con
        // idempotencia por (sessionId, wallBucket)). No toca tablas existentes.
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
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
        }

        // Room 8→9: marca de subida de snapshots de salud (FASE 7). Solo
        // ALTER TABLE (default 0 = pendiente): los snapshots existentes se
        // subirán en el próximo ciclo; no se pierde nada.
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE health_snapshots ADD COLUMN uploadedAt INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "ALTER TABLE health_snapshots ADD COLUMN healthState TEXT NOT NULL DEFAULT 'UNKNOWN'"
                )
            }
        }

        /**
         * F0: columna `funnel` (embudo por bucket). Los snapshots previos
         * quedan con cadena vacía: sin dato inventado.
         */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE health_snapshots ADD COLUMN funnel TEXT NOT NULL DEFAULT ''",
                )
            }
        }

    }
}
