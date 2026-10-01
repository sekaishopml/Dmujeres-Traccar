# Architecture Map

```
apps/panel/src
├── paginas/        Inicio, EnVivo, Historial, Detalle, Replay, Reportes, Bateria, Usuarios, Grupos, Configuracion, Sistema, Login
├── componentes/
│   ├── marco/      Marco (lateral+barra), navegacion.ts (títulos/descr. de página), <AccionesPagina>
│   ├── ui/         Boton, Tarjeta, Cifra, Chip, Tabla, Dialogo, Campo, Avatar, Estados (compartido)
│   ├── mapa/MapaBase.tsx, replay/, expediente/, bateria/, historial/, reportes/, sistema/, admin/
├── dominio/        datos.ts (fetchers+claves caché), estado.ts, formato*.ts, bitacora.ts, replay.ts, rango.ts, errores.ts
├── lib/            api.ts, sesion.ts, tema.ts, cn.ts
└── estilos/index.css   tokens Tailwind (@theme)
```
Alias: `@/` -> `src/`, `@contratos` -> `packages/shared-types/src`.
Otros: `services/{api,tracking,web,routing,recuperacion}`, `database/`, `docs/`, `fallback/` (Android).
