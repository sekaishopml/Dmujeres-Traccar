# TEST STATUS — DMujeres Traccar Platform

> Estado de pruebas con evidencia. Nunca marcar "verificado" sin evidencia.

## FASE 0

| ID | Prueba | Estado | Evidencia |
|---|---|---|---|
| PT-001 | JDK 21 instalado y funcional | ✔ PASÓ | `java -version` → OpenJDK 21.0.11 |
| PT-002 | Clones upstream íntegros (server+web @ 6.14.5) | ✔ PASÓ | `git log` commit 17e7a330 / aa5ca353 |
| PT-003 | Build server Gradle | ⏳ PENDIENTE | — |
| PT-004 | Arranque server + healthcheck `/api/health` | ⏳ PENDIENTE | — |
| PT-005 | Migraciones Liquibase sobre PostgreSQL | ⏳ PENDIENTE | — |
| PT-006 | Build dashboard Vite | ⏳ PENDIENTE | — |
| PT-007 | Server sirve dashboard (E2E minimal) | ⏳ PENDIENTE | — |
| PT-008 | Compose infraestructura levanta (PG/Redis/MQTT) | ⏳ PENDIENTE | — |
| PT-009 | Restauración en entorno limpio (backup/restore) | ⏳ PENDIENTE | — |

## Notas
- Fase 2: pruebas de carga con 1/10/100/1000 dispositivos simulados (tool `test-generator.py` de upstream).
- No se ha ejecutado ningún test de Gradle aún; pendiente de T-006.
- Fases 3-5: sin pruebas definidas (no iniciadas).