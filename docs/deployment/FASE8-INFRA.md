# FASE 8 - Infra de servicios nuevos (systemd + Nginx)

Fecha: 2026-09-25
Alcance: dejar `services/api`, `services/tracking` y `services/web` como servicios
systemd (`dmj-*`) y publicar el panel nuevo por Nginx en el puerto 80.
Produccion: **no modificada** (`dmj-traccar.service`, `dmj-match.service`,
`dmj-traccar-watchdog.*` y `/DMujeres-Tracking` intactos).
Firewall: **no modificado** en esta fase (solo propuestas en la seccion 6).
Sin commits.

---

## 1. Resumen

| Elemento | Valor |
|---|---|
| API propia | `dmj-api.service` -> `127.0.0.1:8081` (`services/api`) |
| Receptor movil | `dmj-tracking.service` -> `127.0.0.1:6066` (`services/tracking`) |
| Panel web | `dmj-web.service` -> `0.0.0.0:25565` (`services/web`) |
| Nginx | `nginx.service` -> `:80` (v4+v6) -> `127.0.0.1:25565` |
| Node | `/usr/local/bin/node24` (Node 24 LTS) |
| Entorno | `EnvironmentFile=/home/DMujeres-Tracking/.env` (600, leido por root) |
| Origen versionado | `infrastructure/production/systemd/`, `infrastructure/production/nginx/` |
| Base de datos | `dmt-db` (Docker, `127.0.0.1:5443`, `restart: unless-stopped`) |

Los tres servicios corrian como procesos sueltos (`node24 src/servidor.js` con
PPID 1). `install.sh` los sustituyo por unidades systemd con `Restart=always`;
ya no hay procesos sueltos de `/home/DMujeres-Tracking`.

---

## 2. Unidades systemd

Archivos en `infrastructure/production/systemd/`:

- `dmj-api.service`
- `dmj-tracking.service`
- `dmj-web.service`

Puntos comunes:

```ini
After=network-online.target docker.service
Wants=network-online.target

[Service]
Type=simple
User=opencode
Group=opencode
WorkingDirectory=/home/DMujeres-Tracking/services/<x>
EnvironmentFile=/home/DMujeres-Tracking/.env
ExecStart=/usr/local/bin/node24 src/servidor.js
Restart=always
RestartSec=3
TimeoutStopSec=15
StandardOutput=journal
StandardError=journal

[Install]
WantedBy=multi-user.target
```

`dmj-web.service` ademas fija el bind publico en la propia unidad:

```ini
Environment=DMJ_WEB_HOST=0.0.0.0
Environment=DMJ_WEB_PORT=25565
```

Decision: **host/puerto del panel van en la unidad**, no en `.env`. El `.env`
queda solo con secretos/config compartida y no gana acoplamiento de red; si
alguien agregara `DMJ_WEB_HOST`/`DMJ_WEB_PORT` al `.env`, systemd le daria
precedencia al archivo (comportamiento documentado de `EnvironmentFile`) y la
unidad perderia el control. Para pasar el panel a `127.0.0.1` (tras confirmar
Nginx en produccion) basta editar la unidad y `daemon-reload` + `restart`;
no se toca `.env`.

Otros detalles:

- `dmj-api.service` usa `DMJ_API_PORT=8081` por defecto del codigo (no esta en
  `.env`); `dmj-tracking.service` toma `DMJ_TRACKING_PORT=6066` del `.env`.
- El apagado es ordenado: las tres fuentes manejan `SIGTERM` y cierran el
  servidor/pool. `TimeoutStopSec=15` da margen sobre los timeouts internos
  (API 10 s, tracking 5 s).
- `dmj-traccar.service` y `dmj-match.service` no se tocaron.

---

## 3. Instalacion y desinstalacion

### Instalar / reinstalar (idempotente)

```sh
/home/DMujeres-Tracking/infrastructure/production/install.sh
```

Pasos que ejecuta:

1. Detecta procesos `node24 src/servidor.js` cuyo `cwd` este bajo
   `/home/DMujeres-Tracking/services` y que **no** pertenezcan ya a una unidad
   `dmj-*.service` (verificacion por `/proc/<pid>/cwd` y cgroup), y los detiene
   (SIGTERM; SIGKILL solo si no cierran en 5 s). Es el equivalente seguro de
   `pkill -f "node24 src/servidor.js"` limitado al proyecto.
2. Copia las unidades a `/etc/systemd/system/` con modo 0644.
3. `systemctl daemon-reload` + `systemctl enable --now dmj-api dmj-tracking dmj-web`.
4. Espera 2 s y muestra `systemctl is-active` + `is-enabled` de las tres.
5. `curl` a `http://127.0.0.1:8081/api/v1/health` y `http://127.0.0.1:25565/`
   (con reintentos cortos); sale con codigo 1 si algo no queda `active`/200.

Re-ejecutarlo no reinicia nada: en la segunda corrida los procesos ya son de
systemd, no hay "sueltos", y `enable --now` es no-op.

### Desinstalar

```sh
/home/DMujeres-Tracking/infrastructure/production/uninstall.sh          # solo unidades
/home/DMujeres-Tracking/infrastructure/production/uninstall.sh --nginx  # unidades + sitio nginx
```

Sin flags: `disable --now`, borra las tres unidades, `daemon-reload` y
`reset-failed`. Con `--nginx`: ademas borra
`/etc/nginx/sites-enabled/dmj-tracking`, `/etc/nginx/sites-available/dmj-tracking`
y la plantilla TLS; **no** purga el paquete nginx ni sus certificados.

---

## 4. Nginx

Instalado con:

```sh
sudo apt-get update && sudo apt-get install -y nginx   # nginx/1.18.0 (Ubuntu)
```

Sitio activo (unico en `sites-enabled`; se elimino el enlace `default`):

- `/etc/nginx/sites-available/dmj-tracking`
  (origen: `infrastructure/production/nginx/dmj-tracking`)
- `/etc/nginx/sites-available/dmj-tracking-tls.example`
  (origen: `infrastructure/production/nginx/dmj-tracking-tls.example`; **no
  habilitado**)

Contenido efectivo del sitio HTTP:

```nginx
server {
    listen 80;
    listen [::]:80;
    server_name _;

    client_max_body_size 10m;

    location / {
        proxy_pass http://127.0.0.1:25565;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_connect_timeout 5s;
        proxy_read_timeout 60s;
        proxy_send_timeout 60s;
    }
}
```

No hay `location /api` aparte: `services/web` ya proxya `/api/*` hacia
`127.0.0.1:8081`, asi que el panel y la API se publican por el mismo origen.

Operaciones utiles (no ejecutadas en esta fase salvo `test`/`enable`/`reload`):

```sh
sudo nginx -t
sudo systemctl enable --now nginx
sudo systemctl reload nginx
```

---

## 5. Verificacion real (2026-09-25 23:43 -05)

Salida de la instalacion:

```
[install] terminando procesos sueltos de /home/DMujeres-Tracking (si existen)
[install] deteniendo proceso suelto pid=431266 (cwd=/home/DMujeres-Tracking/services/api): node24 src/servidor.js
[install] deteniendo proceso suelto pid=431268 (cwd=/home/DMujeres-Tracking/services/web): node24 src/servidor.js
[install] copiando unidades a /etc/systemd/system
[install] daemon-reload + enable --now
Created symlink /etc/systemd/system/multi-user.target.wants/dmj-api.service -> /etc/systemd/system/dmj-api.service.
Created symlink /etc/systemd/system/multi-user.target.wants/dmj-tracking.service -> /etc/systemd/system/dmj-tracking.service.
Created symlink /etc/systemd/system/multi-user.target.wants/dmj-web.service -> /etc/systemd/system/dmj-web.service.
[install] estado de unidades
[install]   dmj-api.service        active=active   enabled=enabled
[install]   dmj-tracking.service   active=active   enabled=enabled
[install]   dmj-web.service        active=active   enabled=enabled
[install] verificacion HTTP local
[install]   GET http://127.0.0.1:8081/api/v1/health -> 200
[install]   GET http://127.0.0.1:25565/                 -> 200
[install] OK
```

Estados tras `sudo systemctl restart dmj-api dmj-tracking dmj-web`:

```
dmj-api        active=active enabled=enabled
dmj-tracking   active=active enabled=enabled
dmj-web        active=active enabled=enabled
nginx          active=active enabled=enabled
dmj-traccar    active=active enabled=enabled
dmj-match      active=active enabled=enabled

nginx 80 local   -> 200
nginx 80 externo -> 200   (http://68.168.20.219/)
api health       -> 200
web 25565        -> 200
viejo 999        -> 200
tracking 6066    -> 400   (OsmAnd sin parametros: el servicio responde)
```

`journalctl -u dmj-tracking` confirma arranque limpio y conexion a la base nueva:

```
[tracking] escuchando en http://127.0.0.1:6066 (entorno=desarrollo,
base=127.0.0.1:5443/dmujeres, canalMovil=activo, claves=2,
otaDir=/DMujeres-Tracking/dashboard/build)
```

Segunda corrida de `install.sh` (idempotencia): los `MainPID` y
`ExecMainStartTimestamp` no cambiaron (siguen en 23:43:24), es decir, no se
reinicio ningun servicio.

Sockets relevantes:

```
0.0.0.0:80 / [::]:80        nginx
0.0.0.0:25565               dmj-web (bloqueado por ufw desde Internet)
127.0.0.1:8081              dmj-api
127.0.0.1:6066              dmj-tracking
*:999 / *:5055              dmj-traccar (produccion, intacto)
```

---

## 6. Puertos abiertos y propuesta de firewall (NO aplicada)

Firewall actual: `ufw` activo, politica por defecto `deny (incoming)`.

Puertos permitidos hoy (v4 y v6), leidos de `ufw status verbose`:

| Puerto | Servicio que escucha | Observacion |
|---|---|---|
| 22/tcp | sshd | administracion; mantener |
| 4096/tcp | proceso `opencode` | revisar si debe ser publico |
| 8000/tcp | `cyhotel-kiosco` (Docker) | produccion ajena a DMujeres; decidir con su dueno |
| 8082/tcp | nadie escucha ahora | regla huerfana; candidata a retirar |
| 1883/tcp | `dmj-mqtt` (Docker) | MQTT sin TLS publico; exposicion mas sensible |
| 999/tcp | `dmj-traccar` panel viejo | mantener hasta el cutover |
| 5055/tcp | `dmj-traccar` OsmAnd | lo usa la App actual; mantener hasta el cutover |

Notas:
- 25565 (`dmj-web`) escucha en `0.0.0.0` pero ufw **no** lo permite: hoy solo es
  alcanzable en local y por Nginx `:80`. No requiere regla.
- 8001/8002 (cyhotel), 5000-5100 (traccar Java) y 18083/8083/5443/6379 escuchan
  en local o estan bloqueados por la politica por defecto.

Propuesta priorizada (ejecutar solo con ventana y aprobacion; comandos de
referencia, **nada de esto se aplico**):

1. **Retirar regla huerfana 8082/tcp** (no hay listener):
   `sudo ufw delete allow 8082/tcp`.
2. **Cerrar 1883/tcp publico** si ningun cliente MQTT externo lo usa (verificar
   `ss -tn state established '( sport = :1883 )'` y consumidores conocidos):
   `sudo ufw delete allow 1883/tcp`. Dejar acceso solo por red Docker/local.
3. **Revisar 4096/tcp** con el dueno del servicio `opencode`; si es una consola
   de desarrollo, cerrarla: `sudo ufw delete allow 4096/tcp`.
4. **Mantener 22, 80, 999 y 5055** hasta el cutover. El 80 es la nueva entrada
   del panel; 999/5055 los sigue usando produccion (panel y App actuales).
5. **Tras el cutover** (cuando la App apunte al receptor nuevo): cerrar
   `999/tcp` (`sudo ufw delete allow 999/tcp`) y reevaluar `5055/tcp`; definir
   entonces como se publica el receptor nuevo (hoy `127.0.0.1:6066`, detras de
   Nginx o regla ufw explicita). Cerrar tambien 8000/8082 si sus duenos
   confirman que no son publicos.

Regla operativa: cualquier bind `0.0.0.0` que no este en la lista de `ufw` esta
bloqueado por la politica por defecto; no agregar `allow` sin justificar.

---

## 7. Pendiente para TLS

La plantilla `/etc/nginx/sites-available/dmj-tracking-tls.example` (origen en
`infrastructure/production/nginx/`) queda **sin habilitar**. Falta:

1. Dominio real con registro A hacia `68.168.20.219` (AAAA solo si la IPv6 del
   host es estable; hoy `ifconfig.me` devuelve IPv6).
2. Instalar el cliente ACME: `sudo apt-get install -y certbot python3-certbot-nginx`.
3. Abrir 443 en ufw en ese momento (no antes): `sudo ufw allow 443/tcp`.
4. Emitir certificado: `sudo certbot --nginx -d tracking.<dominio-real>`
   (la plantilla incluye la alternativa `certonly --webroot`).
5. Renombrar/copiar la plantilla a `dmj-tracking-tls`, habilitarla, `nginx -t`,
   `reload` y redirigir el `:80` a HTTPS conservando
   `/.well-known/acme-challenge/` para renovaciones.
6. Certbot deja `certbot.timer` para renovacion automatica; verificar con
   `systemctl list-timers certbot.timer`.

Nginx 1.18: usar `listen 443 ssl http2;` (la plantilla ya lo advierte; la
directiva `http2 on;` requiere nginx >= 1.25.1).

---

## 8. Dudas / notas para el siguiente agente

- El receptor `dmj-tracking` escucha en `127.0.0.1:6066`. La App movil actual
  sigue apuntando a `dmj-traccar:5055`; cuando toque cutover habra que decidir
  como se publica el 6066 (Nginx stream, regla ufw o cambio en la App) y
  coordinarlo con el firewall.
- `services/web` escucha en `0.0.0.0:25565` por especificacion de esta fase. Lo
  natural tras validar Nginx es pasarlo a `127.0.0.1:25565` (linea `Environment=`
  de `dmj-web.service`), sin cambios de firewall.
- No se verifico que 6066 este accesible para la App porque hoy es local por
  diseno; el smoke de tracking (`node24 src/smoke.mjs`) sigue pendiente de una
  corrida completa contra la base nueva en esta fase.

## Cambio aplicado posteriormente (acceso externo)

El 26-09-2026 se abrieron en ufw los puertos de entrada de la web nueva:

```
sudo ufw allow 80/tcp    comment 'DMujeres Web (Nginx)'
sudo ufw allow 25565/tcp comment 'DMujeres Web (directo, temporal)'
```

Motivo: el firewall solo permitia 22/4096/8000/8082/1883/999/5055, asi que
ni :80 ni :25565 eran alcanzables desde Internet (las pruebas locales hacian
hairpin y no reflejaban la realidad). Verificado desde una red externa:
`http://68.168.20.219/` y `http://68.168.20.219:25565/` responden 200.

Pendiente de la fase de endurecimiento: retirar 25565 cuando Nginx+TLS sea la
unica entrada, y revisar 4096/8000/8082/1883 que siguen abiertos.
