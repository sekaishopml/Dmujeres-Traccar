# Matriz de decisiones — DMujeres Tracking (2026-09-28)

| Componente | Decisión | Motivo | Alternativas descartadas | Riesgo | Responsable | Estado |
|---|---|---|---|---|---|---|
| Motor GPS | FusedLocationProviderClient (flavor `google`) | Fusión de sensores, estándar de facto | LocationManager principal; GNSS crudo | Medio (OEM) | IA-2 | CERRADO |
| Servicio | FGS `type=location` con arranque por acción visible | Requisito Android 12+ | Arranque en background; WorkManager | Bajo | IA-2 | CERRADO |
| Reloj de tracking | `requestLocationUpdates` continuo | Continuidad real | AlarmManager como reloj; WorkManager | Bajo | IA-2 | CERRADO |
| AlarmManager | Solo recuperación, `setAndAllowWhileIdle` 9 min | Único mecanismo que Doze respeta sin permisos | Exact alarm; sin alarma | Medio (intervalo) | IA-2 | CERRADO |
| Wake lock | Solo en envío, timeout 60 s | Consumo medido 834 mAh/24 h si es permanente | Permanente; sin lock | Bajo | IA-2 | CERRADO (ya cumple) |
| Movimiento | Máquina multi-fuente; UNKNOWN→ACTIVE | Deadlock Pilay con acelerómetro solo | Acelerómetro único; Activity Recognition como autoridad | Bajo | IA-2 | CERRADO |
| Cadencia | 5-10 s activo / 30-120 s parado, sin batching | Fidelidad del trazo | Batching agresivo | Bajo | IA-2 | CERRADO |
| Store local | SQLite + meta (boot, secuencia, jornada) | Durable, sin dependencias | Room; memoria | Bajo | IA-2 | CERRADO |
| Identidad | `(device_id, boot_id, local_sequence)` | Dedupe y orden sin UUID por evento | Timestamp; payload hash | Bajo | IA-2 | CERRADO |
| Ingesta | Idempotente + lote `/positions` + regla de avance de viva | Cero duplicados; atrasados sin retroceso | Dedupe por hash; sin lotes | Bajo | IA-2 | CERRADO |
| Jornada | `GET /journey` + JourneyManager persistente | Reconciliación tras reboot/recreación | Solo POST; solo RAM | Bajo | IA-2 | CERRADO |
| Cola | Clasificación HTTP + backoff exp+jitter | Evitar bloqueo y golpeteo | Retry fijo 30 s | Bajo | IA-2 | CERRADO |
| Replay | REAL / MATCHED / ESTIMATED separados y rotulados | Regla de oro (auditable) | Estilo único "ruta normal" (revocado) | Bajo | IA-2 | CERRADO |
| GIS | GraphHopper: `/route` + `/match` + gap reconstruction, cache/versionado | Honestidad de reconstrucción | OSRM; APIs públicas; sin GIS | Bajo | IA-2 | CERRADO |
| Paradas | Umbral velocidad + radio + tiempo + absorción de jitter (actual) | Ya validado con Pilay (28→5) | Clustering agresivo | Bajo | — | CERRADO (conservar) |
| Base de datos | PostgreSQL + TimescaleDB, sin PostGIS | Sin consultas espaciales que lo justifiquen | PostGIS por moda | Bajo | — | CERRADO |
| Particiones | Precreación mes+2 + on-write + validación de fechas | Evitar partición basura y escrituras sin partición | Manual; solo on-write | Bajo | IA-2 | CERRADO |
| Diagnóstico | Salud derivada en servidor con causa visible | Operación real, no solo OFFLINE mudo | Solo latido crudo | Bajo | IA-2 | CERRADO |
| Seguridad | TLS+HSTS+cookies seguras y sin credenciales en repo | Entrega empresarial | Dejarlo para después | Medio (diferido por dueño) | IA-2 | CERRADO (gate de entrega) |
| Release | versionCode 284/2.1.74 commiteado + keystore por canal seguro | Reproducibilidad | Seguir sin commitear | Bajo | IA-2 | CERRADO |
