/*
 * Copyright 2015 - 2022 Anton Tananaev (anton@traccar.org)
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
@file:Suppress("DEPRECATION", "StaticFieldLeak")
package org.traccar.client

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.database.SQLException
import android.database.DatabaseUtils
import android.util.Log
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.AsyncTask
import java.sql.Date

private const val MAX_BUFFERED_POSITIONS = 5000L

private const val TAG = "DatabaseHelper"

class DatabaseHelper(context: Context?) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    interface DatabaseHandler<T> {
        fun onComplete(success: Boolean, result: T)
    }

    private abstract class DatabaseAsyncTask<T>(val handler: DatabaseHandler<T?>) : AsyncTask<Unit, Unit, T?>() {

        private var error: RuntimeException? = null

        override fun doInBackground(vararg params: Unit): T? {
            return try {
                executeMethod()
            } catch (error: RuntimeException) {
                this.error = error
                null
            }
        }

        protected abstract fun executeMethod(): T

        override fun onPostExecute(result: T?) {
            handler.onComplete(error == null, result)
        }
    }

    private val db: SQLiteDatabase = writableDatabase

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(CREATE_POSITION_V5)
        db.execSQL(CREATE_META)
    }

    /**
     * Migración ADITIVA (ADR-004): nunca DROP en upgrade, para no perder la
     * cola offline ni la secuencia al actualizar la APK en calle. Cada versión
     * aplica solo sus ALTER/CREATE con IF NOT EXISTS.
     */
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 4) {
            // Esquema anterior a la política de no-DROP: se conserva el
            // comportamiento histórico para esas versiones viejas.
            db.execSQL("DROP TABLE IF EXISTS position;")
            onCreate(db)
            return
        }
        if (oldVersion < 5) {
            for (column in POSITION_V5_COLUMNS) {
                runCatching { db.execSQL("ALTER TABLE position ADD COLUMN $column") }
            }
            runCatching { db.execSQL(CREATE_META) }
        }
    }

    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS position;")
        onCreate(db)
    }

    fun insertPosition(position: Position) {
        // La identidad la asigna el almacén, no quien captura: así cada fix
        // aceptado tiene (boot_id, secuencia) único y persistente.
        val identified = withIdentity(position)
        val values = ContentValues()
        values.put("deviceId", identified.deviceId)
        values.put("time", identified.time.time)
        values.put("latitude", identified.latitude)
        values.put("longitude", identified.longitude)
        values.put("altitude", identified.altitude)
        values.put("speed", identified.speed)
        values.put("course", identified.course)
        values.put("accuracy", identified.accuracy)
        values.put("battery", identified.battery)
        values.put("charging", if (identified.charging) 1 else 0)
        values.put("mock", if (identified.mock) 1 else 0)
        values.put("boot_id", identified.bootId)
        values.put("local_sequence", identified.localSequence)
        values.put("journey_id", identified.journeyId)
        values.put("provider", identified.provider)
        values.put("movement_state", identified.movementState)
        values.put("status", identified.status)
        values.put("attempts", identified.attempts)
        db.insertOrThrow("position", null, values)
        enforceBufferLimit(db)
    }

    /**
     * Tope del búfer offline (política drop_oldest, igual que la app nativa):
     * si se supera [MAX_BUFFERED_POSITIONS] se descartan los más viejos para no
     * crecer sin límite en cortes largos de red. 5.000 puntos ≈ 3 jornadas
     * completas de 10 h (calculado con la cadencia real: 1/min quieto, 4/min en
     * marcha) y ~1,2 MB de SQLite.
     *
     * El descarte ya no es silencioso: cuenta en `meta(buffer_overflow)` y se
     * reporta en el latido, para que el panel explique el hueco (regla de oro).
     */
    private fun enforceBufferLimit(db: SQLiteDatabase) {
        runCatching {
            val count = DatabaseUtils.queryNumEntries(db, "position")
            if (count <= MAX_BUFFERED_POSITIONS) return
            val excess = count - MAX_BUFFERED_POSITIONS
            db.execSQL(
                "DELETE FROM position WHERE id IN (SELECT id FROM position ORDER BY id ASC LIMIT ?)",
                arrayOf(excess),
            )
            Log.w(TAG, "búfer al tope: descartados $excess puntos más viejos")
            StatusActivity.addMessage("Búfer lleno: se descartaron $excess puntos más viejos")
            addMetaCounter(db, KEY_BUFFER_OVERFLOW, excess)
        }.onFailure { Log.w(TAG, "no se pudo aplicar el tope del búfer", it) }
    }

    fun insertPositionAsync(position: Position, handler: DatabaseHandler<Unit?>) {
        object : DatabaseAsyncTask<Unit>(handler) {
            override fun executeMethod() {
                insertPosition(position)
            }
        }.execute()
    }

    @SuppressLint("Range")
    /** Número de ubicaciones en el buffer (cuadro "Pendientes por enviar"). */
    fun countPositions(): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM position", null).use { cursor ->
            if (cursor.moveToFirst()) return cursor.getInt(0)
        }
        return 0
    }

    fun selectPosition(): Position? {
        // Serie estricta: siempre el más viejo primero (read->send->ack->delete).
        return selectPositions(1).firstOrNull()
    }

    @SuppressLint("Range")
    /** Lote ordenado por inserción para la subida en serie (hasta [limit]). */
    fun selectPositions(limit: Int): List<Position> {
        val out = ArrayList<Position>()
        db.rawQuery("SELECT * FROM position ORDER BY id LIMIT ?", arrayOf(limit.toString())).use { cursor ->
            while (cursor.moveToNext()) {
                out.add(positionFromCursor(cursor))
            }
        }
        return out
    }

    @SuppressLint("Range")
    /**
     * Últimos fixes para reconstruir la máquina de estados tras recrear el
     * proceso (el estado nunca se asume de la RAM).
     */
    fun selectRecentPositions(limit: Int): List<Position> {
        val out = ArrayList<Position>()
        db.rawQuery("SELECT * FROM position ORDER BY id DESC LIMIT ?", arrayOf(limit.toString())).use { cursor ->
            while (cursor.moveToNext()) {
                out.add(positionFromCursor(cursor))
            }
        }
        return out
    }

    @SuppressLint("Range")
    private fun positionFromCursor(cursor: android.database.Cursor): Position {
        // getColumnIndex devuelve -1 en bases viejas sin migrar: se lee con
        // valor por defecto en vez de romper la lectura.
        fun text(name: String): String {
            val i = cursor.getColumnIndex(name)
            return if (i >= 0 && !cursor.isNull(i)) cursor.getString(i) else ""
        }
        fun long(name: String): Long {
            val i = cursor.getColumnIndex(name)
            return if (i >= 0 && !cursor.isNull(i)) cursor.getLong(i) else 0L
        }
        fun int(name: String): Int {
            val i = cursor.getColumnIndex(name)
            return if (i >= 0 && !cursor.isNull(i)) cursor.getInt(i) else 0
        }
        return Position(
            id = cursor.getLong(cursor.getColumnIndex("id")),
            deviceId = cursor.getString(cursor.getColumnIndex("deviceId")),
            time = Date(cursor.getLong(cursor.getColumnIndex("time"))),
            latitude = cursor.getDouble(cursor.getColumnIndex("latitude")),
            longitude = cursor.getDouble(cursor.getColumnIndex("longitude")),
            altitude = cursor.getDouble(cursor.getColumnIndex("altitude")),
            speed = cursor.getDouble(cursor.getColumnIndex("speed")),
            course = cursor.getDouble(cursor.getColumnIndex("course")),
            accuracy = cursor.getDouble(cursor.getColumnIndex("accuracy")),
            battery = cursor.getDouble(cursor.getColumnIndex("battery")),
            charging = cursor.getInt(cursor.getColumnIndex("charging")) > 0,
            mock = cursor.getInt(cursor.getColumnIndex("mock")) > 0,
            bootId = text("boot_id"),
            localSequence = long("local_sequence"),
            journeyId = text("journey_id"),
            provider = text("provider"),
            movementState = text("movement_state"),
            status = text("status").ifBlank { STATUS_PENDING },
            attempts = int("attempts"),
        )
    }

    fun selectPositionAsync(handler: DatabaseHandler<Position?>) {
        object : DatabaseAsyncTask<Position?>(handler) {
            override fun executeMethod(): Position? {
                return selectPosition()
            }
        }.execute()
    }

    fun deletePosition(id: Long) {
        if (db.delete("position", "id = ?", arrayOf(id.toString())) != 1) {
            throw SQLException()
        }
    }

    /** Borrado de un lote ya confirmado (idempotente: ignora los que falten). */
    fun deletePositions(ids: List<Long>) {
        if (ids.isEmpty()) return
        db.beginTransaction()
        try {
            for (id in ids) {
                db.delete("position", "id = ?", arrayOf(id.toString()))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Marca DEAD (no reintentar) y cuenta para el reporte de diagnóstico. */
    fun markDead(ids: List<Long>) {
        if (ids.isEmpty()) return
        db.beginTransaction()
        try {
            for (id in ids) {
                val values = ContentValues()
                values.put("status", STATUS_DEAD)
                db.update("position", values, "id = ?", arrayOf(id.toString()))
            }
            addMetaCounter(db, KEY_DEAD_EVENTS, ids.size.toLong())
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        // Los DEAD no bloquean la cola: se borran tras contarlos, el reporte
        // queda en meta (el servidor ya dijo que no los quiere).
        deletePositions(ids)
    }

    /** Suma un intento de subida a cada evento del lote. */
    fun addAttempts(ids: List<Long>) {
        if (ids.isEmpty()) return
        db.beginTransaction()
        try {
            for (id in ids) {
                db.execSQL("UPDATE position SET attempts = attempts + 1 WHERE id = ?", arrayOf(id))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    // --- Tabla meta: identidad y contadores persistentes ---------------------

    @SuppressLint("Range")
    fun getMeta(clave: String): String? {
        readableDatabase.rawQuery("SELECT valor FROM meta WHERE clave = ?", arrayOf(clave)).use { cursor ->
            if (cursor.moveToFirst()) return cursor.getString(0)
        }
        return null
    }

    fun putMeta(clave: String, valor: String) {
        val values = ContentValues()
        values.put("clave", clave)
        values.put("valor", valor)
        db.insertWithOnConflict("meta", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun addMetaCounter(db: SQLiteDatabase, clave: String, delta: Long) {
        runCatching {
            val current = db.rawQuery("SELECT valor FROM meta WHERE clave = ?", arrayOf(clave)).use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0).toLongOrNull() ?: 0L else 0L
            }
            val values = ContentValues()
            values.put("clave", clave)
            values.put("valor", (current + delta).toString())
            db.insertWithOnConflict("meta", null, values, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    /** Contador de desbordes del búfer (para el latido). */
    fun bufferOverflowCount(): Long = getMeta(KEY_BUFFER_OVERFLOW)?.toLongOrNull() ?: 0L

    /** Eventos declarados DEAD por el servidor (para el latido). */
    fun deadEventsCount(): Long = getMeta(KEY_DEAD_EVENTS)?.toLongOrNull() ?: 0L

    /**
     * `boot_id`: UUID por arranque DE PROCESO. Se rota al arrancar el tracking
     * ([rotateBootId]) y se persiste en meta para que sobreviva a la recreación
     * (las filas viejas conservan su boot_id: la identidad es por evento).
     */
    @Synchronized
    fun currentBootId(): String {
        val existing = getMeta(KEY_BOOT_ID)
        if (!existing.isNullOrBlank()) return existing
        return rotateBootId()
    }

    @Synchronized
    fun rotateBootId(): String {
        val fresh = java.util.UUID.randomUUID().toString()
        putMeta(KEY_BOOT_ID, fresh)
        return fresh
    }

    /**
     * `local_sequence`: contador persistente que SOLO incrementa (nunca se
     * reinicia, ni en reboot). Sincronizado porque la captura y la subida
     * comparten el SQLite desde hilos distintos.
     */
    @Synchronized
    fun nextLocalSequence(): Long {
        val current = getMeta(KEY_LAST_SEQUENCE)?.toLongOrNull() ?: 0L
        val next = current + 1
        putMeta(KEY_LAST_SEQUENCE, next.toString())
        return next
    }

    /** Completa la identidad del evento si quien captura no la puso. */
    fun withIdentity(position: Position): Position {
        if (position.bootId.isNotBlank() && position.localSequence > 0L) return position
        return position.copy(
            bootId = position.bootId.ifBlank { currentBootId() },
            localSequence = if (position.localSequence > 0L) position.localSequence else nextLocalSequence(),
            journeyId = position.journeyId.ifBlank { getMeta(KEY_JOURNEY_ID).orEmpty() },
            status = position.status.ifBlank { STATUS_PENDING },
        )
    }

    fun deletePositionAsync(id: Long, handler: DatabaseHandler<Unit?>) {
        object : DatabaseAsyncTask<Unit>(handler) {
            override fun executeMethod() {
                deletePosition(id)
            }
        }.execute()
    }

    companion object {
        const val DATABASE_VERSION = 5
        const val DATABASE_NAME = "traccar.db"

        /** Columnas nuevas de la v5 (migración aditiva). */
        private val POSITION_V5_COLUMNS = listOf(
            "boot_id TEXT",
            "local_sequence INTEGER",
            "journey_id TEXT",
            "provider TEXT",
            "movement_state TEXT",
            "status TEXT",
            "attempts INTEGER",
        )

        private const val CREATE_POSITION_V5 =
            "CREATE TABLE position (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "deviceId TEXT," +
                "time INTEGER," +
                "latitude REAL," +
                "longitude REAL," +
                "altitude REAL," +
                "speed REAL," +
                "course REAL," +
                "accuracy REAL," +
                "battery REAL," +
                "charging INTEGER," +
                "mock INTEGER," +
                "boot_id TEXT," +
                "local_sequence INTEGER," +
                "journey_id TEXT," +
                "provider TEXT," +
                "movement_state TEXT," +
                "status TEXT," +
                "attempts INTEGER)"

        private const val CREATE_META =
            "CREATE TABLE IF NOT EXISTS meta (clave TEXT PRIMARY KEY, valor TEXT)"

        const val KEY_BOOT_ID = "boot_id"
        const val KEY_LAST_SEQUENCE = "ultima_secuencia"
        const val KEY_JOURNEY_ID = "journey_id"
        const val KEY_JOURNEY_STARTED_AT = "journey_started_at"
        const val KEY_BUFFER_OVERFLOW = "buffer_overflow"
        const val KEY_DEAD_EVENTS = "dead_events"
        const val KEY_RECOVERY_PENDING = "recovery_pending"
    }

}
