# Sentry — reporte de crashes de la app

✅ **ACTIVO desde la 1.0.70** (proyecto `DMujeres Tracking`, org `sekaidev-w5`).
Probado punta a punta: evento de prueba enviado y visible en Issues.

## Cómo quedó configurado

- SDK `sentry-android` + init en `DmujeresApp` (crashes, ANRs, entorno
  debug/production, release `com.dmujeres.traccar@versión+código`).
  Sin screenshots ni IP por privacidad.
- DSN en `SENTRY_DSN` (`app/build.gradle.kts`).
- Plugin Gradle `io.sentry.android.gradle 4.14.1`: sube el mapping R8 en
  cada release → pilas legibles. El token (`SENTRY_AUTH_TOKEN`) **nunca**
  va en el repo: solo como variable de entorno al compilar releases.

## Si rotas el token o el DSN

1. Sentry → Settings → Auth Tokens (revocar/crear).
2. Proyecto → Settings → Client Keys (nuevo DSN) → pégalo en `SENTRY_DSN`.
3. Servidor: compila releases con `SENTRY_AUTH_TOKEN=<nuevo>` en el entorno.

## Ver crashes

Sentry → proyecto `DMujeres Tracking` → **Issues**: verás modelo del
teléfono, versión de app, Android y la pila desofuscada. Plan gratuito:
5.000 eventos/mes (suficiente; un crash por teléfono genera 1 evento).
