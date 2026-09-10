package com.dmujeres.traccar.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [PendingPosition::class, SequenceState::class], version = 6, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    abstract fun positionDao(): PositionDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "dmj_tracking.db"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6).build().also { instance = it }
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
    }
}
