# DMujeres Tracking

Rastreo GPS para los colaboradores de DMujeres. Fork de Traccar 6.14.5.

## Carpetas

- `server/` – servidor Traccar (Java) con los endpoints que usa la app.
- `dashboard/` – panel web para ver dispositivos, rutas y reportes.
- `fallback/` – app Android actual (cliente Traccar adaptado).
- `mobile/` – app Android anterior, ya no se usa.
- `infrastructure/` – scripts de compilación, respaldo y publicación.

## Compilar la app

```bash
cd fallback
./gradlew assembleGoogleRelease
```

El APK queda en `fallback/app/build/outputs/apk/google/release/`.

## Publicar una versión

```bash
bash infrastructure/scripts/build-pilot.sh
```

Suma una versión, compila el APK, lo publica para los teléfonos dados de alta
y deja listo el aviso de actualización.

## Servidor

Corre como servicio `dmj-traccar`, con la base de datos en el contenedor
`dmj-db`. Para desarrollo: copiar `.env.example` a `.env`, completar los
secretos y levantar la infraestructura con `docker compose up -d`.

Los respaldos se hacen con `infrastructure/scripts/backup.sh`.
