import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import { fileURLToPath } from 'node:url';

// La web habla solo con la API propia (/api/v1). En desarrollo Vite proxya al
// servicio api; en la entrega, services/web sirve el build y hace de proxy.
export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      '@contratos': fileURLToPath(new URL('../../packages/shared-types/src', import.meta.url)),
    },
  },
  server: {
    host: '127.0.0.1',
    port: 5173,
    proxy: { '/api': 'http://127.0.0.1:8081' },
    fs: { allow: ['..', '../..'] },
  },
  preview: {
    host: '127.0.0.1',
    port: 4173,
    proxy: { '/api': 'http://127.0.0.1:8081' },
  },
  build: {
    outDir: 'dist',
    sourcemap: false,
    rolldownOptions: {
      output: {
        // Vite 8 usa rolldown: codeSplitting.groups reemplaza al manualChunks
        // de Rollup (deprecado). Los grupos separan las dependencias grandes y
        // estables en chunks propios con nombre fijo, de modo que un cambio en
        // la app no invalide su caché. El mapa y los gráficos solo se descargan
        // en las rutas que los usan (En vivo, Replay, Detalle, Batería).
        //
        // Prioridad: rolldown captura también las dependencias recursivas de
        // cada grupo (includeDependenciesRecursively), así que "vendor" va
        // primero para quedarse con React y evitar que el grupo de gráficos lo
        // arrastre al chunk de chart.js, que no debe cargar la entrada.
        codeSplitting: {
          groups: [
            {
              name: 'vendor',
              test: /node_modules[\\/](react|react-dom|scheduler|react-router|react-router-dom|@tanstack|zustand|use-sync-external-store)[\\/]/,
              priority: 30,
            },
            { name: 'mapas', test: /node_modules[\\/](maplibre-gl|@maplibre|@mapbox)[\\/]/, priority: 20 },
            { name: 'graficos', test: /node_modules[\\/](chart\.js|react-chartjs-2|@kurkle)[\\/]/, priority: 10 },
          ],
        },
      },
    },
  },
});
