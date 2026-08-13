# PROJECT CONTEXT — DMujeres Traccar Platform

> Documento de estado continuo. Cualquier agente de IA o humano debe poder
> retomar el proyecto leyendo este archivo + ROADMAP.md + CURRENT_TASK.md.

## Qué es

Plataforma empresarial de tracking GPS (hard fork de Traccar) con:

- **Server**: fork de `traccar/traccar` (Java 21, Jetty 12, Jersey 4, Guice 7, Netty 4.2)
- **Dashboard**: fork de `traccar/traccar-web` (React 19, MUI 9, Redux Toolkit, MapLibre GL 5)
- **Mobile**: app Android nueva en Kotlin (FASE 3, MVP compilado)

## Alcance (decisión D-012)

Plataforma **privada y confidencial de UNA sola empresa**. **Sin multi-tenancy**:
se mantiene el modelo plano de Traccar (usuarios/grupos/dispositivos con permisos).
La seguridad se centra en proteger el acceso al entorno (TLS, auth, firewall/VPN, backups).

## Estado actual

- **Fase**: FASE 4 completada; FASE 5 (hardening y producción) pendiente
- **Upstream**: 6.14.5 (server y web)
- **Licencia upstream**: Apache 2.0 (ambos repos)

## Entorno de desarrollo (VPS actual)

| Recurso | Valor |
|---|---|
| OS | Ubuntu 24.04, kernel 6.8 (Vultr) |
| CPU/RAM | 8 cores / 31 GB |
| Disco | 480 GB (424 GB libres) |
| Docker | 29.1.3 + Compose 2.40.3 |
| Node | v20.20.2 |
| Java | OpenJDK 21.0.11 (apt) |
| Directorio | `/DMujeres-Traccar` |

## Estructura del monorepo

```
/DMujeres-Traccar
├── server/          ← fork de traccar (rama: dev, launchpad: master/upstream)
├── dashboard/       ← fork de traccar-web (rama: dev, launchpad: master/upstream)
├── mobile/          ← app Android (pendiente FASE 3)
├── docs/            ← documentación (architecture, adr, api, database, ...)
├── infrastructure/  ← docker compose, scripts, .env
├── PROJECT_CONTEXT.md  ← este archivo
├── ROADMAP.md
├── CURRENT_TASK.md
├── ARCHITECTURE.md
├── DECISIONS.md
├── CHANGELOG.md
└── TEST_STATUS.md
```

## Reglas de oro

1. TODO código se valida con evidencia (tests/comandos/logs). Nunca "verificado" sin prueba.
2. Reutilizar Traccar antes de reinventar. No duplicar componentes maduros.
3. No publicar (git push) sin autorización explícita.
4. No commits de secretos. Config siempre vía env/archivos no versionados.
5. La infraestructura debe reconstruirse desde cero (Docker Compose + scripts).
6. Ports/dominios: nunca IPs del VPS en el código de la app; usar dominios estables.