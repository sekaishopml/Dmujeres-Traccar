# Dmujeres-Traccar — Hard-Fork Bare-Metal Optimizado

Este proyecto es un hard-fork de **Traccar v6.14.5** optimizado para un rendimiento a largo plazo de grado empresarial, con cero errores y consumo mínimo de recursos.

## Arquitectura del Sistema
- **Backend**: Go (Fiber v2) reemplaza por completo el backend original en Java. Consume < 30MB de RAM y gestiona conexiones concurrentes nativas de manera extremadamente eficiente.
- **Database**: PostgreSQL 16 + PostGIS + TimescaleDB (automatic partitioning, 7-day compression, 90-day retention policies) para evitar que el almacenamiento del servidor se sature.
- **Caché y Realtime**: Redis 7.0 (Pub/Sub) + WebSockets en Go para la sincronización de posiciones en tiempo real.
- **Frontend**: React + Material UI original (manteniendo el 100% de la estética original), adaptado para usar **Google Carreteras (Google Roads)**.
- **Optimización de Mapas**: Nginx local proxy con caché en disco local por 30 días para evitar bloqueos por parte de Google y optimizar las consultas repetitivas de mapas.
- **Optimización de Playback**: Replay reescrito con `requestAnimationFrame` y selector de velocidades (1x hasta 100x), complementado con la simplificación de rutas via PostGIS (`ST_SimplifyPreserveTopology`) en base de datos.

---

## Requisitos de Instalación
- Ubuntu 22.04 o 24.04 LTS (Bare-Metal / VPS)
- Acceso root o sudo

---

## Puesta en Marcha Rápida (Primer Setup)
Ejecuta el script de configuración automática como root:
```bash
sudo ./scripts/setup.sh
```
El script instalará automáticamente las dependencias del sistema (Go, Node.js, Nginx, Redis, PostgreSQL con extensiones), compilará el backend/frontend, e iniciará el servicio de Nginx y del servidor de Go.

---

## Referencia de Comandos (Makefile)
Utiliza `make` en la raíz del proyecto `/home/Dmujeres-Traccar` para realizar tareas de mantenimiento cotidianas:

- `make build`          - Compila el backend de Go y compila los estáticos del frontend de React.
- `make deploy`         - Copia las configuraciones locales de Nginx y Systemd a sus carpetas del sistema, recarga la configuración de Nginx y el daemon de Systemd, y reinicia ambos servicios.
- `make restart`        - Reinicia Nginx y el backend de Go.
- `make logs`           - Muestra los logs en tiempo real (journalctl) del backend Go.
- `make status`         - Muestra el estado actual del Go backend, Nginx, PostgreSQL y Redis.
- `make clean`          - Elimina los compilados temporales y binarios del proyecto.

---

## Estructura del Repositorio
```
/home/Dmujeres-Traccar
├── README.md               # Este archivo
├── Makefile                # Atajos de compilación y despliegue
├── backend/                # Servidor Go + Fiber
│   ├── cmd/server/main.go  # Entrypoint del backend
│   └── internal/           # Lógica, bases de datos y handlers
├── frontend/               # Cliente React + Material UI
│   ├── src/                # Código fuente React
│   └── vite.config.js      # Configuración de Vite
├── nginx/                  # Configuración de proxy de Nginx
│   └── dmujeres.conf       # Servidor estático, API proxy y Tiles cache
├── scripts/                # Scripts de mantenimiento
│   └── setup.sh            # Script de inicialización automatizada
└── systemd/                # Configuración de servicios de Linux
    └── dmujeres.service    # Servicio para levantar y vigilar el backend Go
```
