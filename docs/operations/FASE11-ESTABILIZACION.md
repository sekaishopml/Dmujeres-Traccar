# FASE 11 — Estabilización (26/09/2026)

La plataforma nueva quedó como servidor tras el cutover. Esta fase cubre la
parte funcional; **TLS y firewall se difieren a producción** por decisión del
dueño.

## Recuperación por push FCM (nueva)

- Servicio `dmj-recuperacion` (systemd, activo): cada 60 s busca equipos
  habilitados con token FCM, jornada activa o presencia offline y silencio
  ≥ 15 min; aplica cooldown 60 s y máximo 5/hora; envía el probe
  `TRACKING_RECOVERY_PROBE` por FCM HTTP v1 (JWT RS256 con la cuenta de
  servicio, sin SDK) y audita en `operations.dmt_alerta` y atributos
  `mobile.recovery*`.
- **Prueba real:** primer ciclo con `dryRun=false` envió probes a Manzaba y
  Fernando (messageId de FCM) y **ambos acusaron en ~1 s** (`recovery_ack` en
  la base), confirmando el ciclo completo con la flota.
- Detalle en `docs/operations/FASE11-RECUPERACION.md`.

## Respaldos de la base nueva

- `scripts/backup/backup-dmujeres.sh`: dump custom de `dmujeres`, verificado
  con `pg_restore --list`, copia del `.env` (600) y SHA-256, con retención de
  14 días en `/home/DMujeres-backups/nueva/`.
- Cron `/etc/cron.d/dmj-nueva-backup` (diario 03:30). Primera corrida
  verificada: dump de 7,3 MB.

## Otros

- `dmj-match` (map-matching, 127.0.0.1:8991) sigue activo y responde; es
  autocontenido (grafo GraphHopper) y lo usaba el panel viejo. Su retiro se
  decide en la FASE 12.
- E2E: **24/24 PASS** tras la estabilización.
- Seguridad (TLS/dominio y endurecimiento de firewall): **diferida** a la
  puesta en producción, como pidió el dueño.
