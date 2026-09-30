# 2026-09-30 — Login sin mayúsculas, miembros de grupo, línea completa y avisos del cronograma (panel 1.10.0)

## Login (api/auth.js y tracking/movil.js, servicios reiniciados)
- La app manda el usuario en minúsculas; la cuenta "Adriana" se creó con mayúscula y el servidor comparaba exacto → 401. Ahora `lower(nombre_usuario) = lower($1) OR lower(correo) = lower($1)`. No hay usuarios que choquen por mayúsculas.

## Grupos (paginas/Grupos.tsx)
- El listado solo trae `totalMiembros`; el diálogo abría todo desmarcado y "Guardar lista" vaciaba el grupo. Ahora lee `GET /api/v1/grupos/:id` y muestra "En el grupo (n)" marcadas y "Disponibles para sumar" (dadas de baja al final).

## Repetición de ruta
- Se retiraron los círculos huecos de ubicación aproximada; la línea pasa otra vez por todos los fixes. El servidor sigue sin usar los de >50 m para el ajuste a calles.
- Pares "quieto" con desplazamiento >=40 m fuera de paradas se dibujan (los fixes de antena llegan con velocidad 0 y la línea quedaba cortada).

## Avisos del cronograma
- Migración `006_cronograma_visto.sql` (aplicada): `operations.dmt_cronograma_visto(usuario_id, dispositivo_id, visto_en)`.
- API: `GET /api/v1/cronograma/novedades` (actividades con `actualizado_en` posterior a la marca; sin marca, última semana) y `POST /api/v1/cronograma/visto {dispositivoId}`.
- Panel: `componentes/reportes/novedades.ts`; número magenta en "Reportes" del menú (**se tocó `componentes/marco/Marco.tsx`**, también con el menú plegado); lista de personas propia con círculo magenta y número por persona; abrir el cronograma de una persona la marca como vista. Refresco cada 60 s.
