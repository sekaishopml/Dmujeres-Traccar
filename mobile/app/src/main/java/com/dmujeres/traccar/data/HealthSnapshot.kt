package com.dmujeres.traccar.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Snapshot local de salud del tracking (heartbeat local del proceso).
 *
 * DEMUESTRA que el proceso sigue vivo: se persiste con room incluso con red
 * caída. NO es un heartbeat GPS ni genera tráfico por sí solo: el envío al
 * servidor ocurre por los canales existentes (DiagnosticsReporter/presencia)
 * cuando corresponde.
 *
 * Idempotencia: la clave única (sessionId, wallBucket) evita duplicados si el
 * mismo bucket se genera dos veces (reinicio de sesión con mismo id, escrituras
 * concurrentes). wallBucket = minuto de pared del snapshot.
 */
@Entity(
    tableName = "health_snapshots",
    indices = [
        Index(value = ["sessionId", "wallBucket"], unique = true),
        Index(value = ["wallMs"]),
    ],
)
data class HealthSnapshot(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    val deviceId: String,
    @ColumnInfo(defaultValue = "0")
    val wallMs: Long,
    /** Bucket de idempotencia: minuto de pared del snapshot. */
    @ColumnInfo(defaultValue = "0")
    val wallBucket: Long,
    @ColumnInfo(defaultValue = "0")
    val elapsedMs: Long,
    val eventType: String,
    val reason: String,
    @ColumnInfo(defaultValue = "0")
    val fgs: Boolean,
    val motion: String,
    val network: String,
    @ColumnInfo(defaultValue = "0")
    val outbox: Int,
    @ColumnInfo(defaultValue = "1")
    val payloadVersion: Int = 1,
    /** Estado de salud derivado (TrackingHealthPolicy) al momento del snapshot. */
    @ColumnInfo(defaultValue = "UNKNOWN")
    val healthState: String = "UNKNOWN",
    /** 0 = pendiente de subir al servidor; >0 = epoch ms del ACK de ingesta. */
    @ColumnInfo(defaultValue = "0")
    val uploadedAt: Long = 0L,
    /**
     * F0: embudo del bucket (JSON compacto, [com.dmujeres.traccar.health.
     * HealthFunnelPolicy]): recibidos/rechazados por motivo/encolados/ack/
     * segundos en movimiento. Permite medir cobertura con denominador
     * independiente de los propios fixes.
     */
    @ColumnInfo(defaultValue = "''")
    val funnel: String = "",
)
