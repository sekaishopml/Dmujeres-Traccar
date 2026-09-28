# ADR-001 — Motor de tracking

Estado: **CERRADO** (2026-09-28).

## Contexto

La app `fallback/` (org.traccar.client) captura con `AndroidPositionProvider`
(LocationManager, flavor `regular`) o `GooglePositionProvider`
(FusedLocationProviderClient, flavor `google`). La APK de flota distribuida usa
Firebase (hay `fcmTokenPrefix` en producción) → flavor `google` con Fused.

## Decisión

El motor oficial de captura es **FusedLocationProviderClient** dentro del
flavor `google` (producción). El flavor `regular` se mantiene solo para builds
locales sin Google Play Services y no se distribuye ni se considera soportado.

## Alternativas descartadas

- LocationManager como motor principal: peor fusión de sensores y más consumo.
- GNSS crudo (GnssStatus): innecesario; Fused ya encapsula.

## Consecuencias

- El código de producción vive en `fallback/app/src/google/java/...`
  (`GooglePositionProvider.kt`) y debe ser el que reciba mejoras.
- Los cambios de motor en IA-2 van a `PositionProvider`/`TrackingController`,
  no al flavor `regular`.
- VALIDAR EN FASE DE TESTING: supervivencia real del Fused en OEM duros.
