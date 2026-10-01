# Common Mistakes

1. **Título propio en páginas del panel**: el título/descripción salen de `componentes/marco/navegacion.ts`. Filtros/acciones van en `<AccionesPagina>`.
2. **Editar archivos compartidos** (`componentes/ui`, `marco`, `dominio`, `lib`, `estilos`): evitarlo; crear componentes en `componentes/<area>/`.
3. **Datos inventados**: usar `GUION` de `@/dominio/formatoBase` cuando falte el dato.
4. **Magenta (`marca`) en superficies grandes**: solo acción principal, foco y elemento activo.
5. **Layout no responsive**: debe usarse a 360 px sin scroll horizontal de página (las tablas scrollean dentro de su tarjeta).
6. **Compilar con Node viejo**: usar Node 24 (`/opt/node24/bin`), ver QUICK_START.
7. **Olvidar subir `version`** en `apps/panel/package.json`.
