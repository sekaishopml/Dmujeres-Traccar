import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';
import { fileURLToPath } from 'node:url';

// Panel DMujeres Tracking. Habla solo con la API propia (/api/v1): en
// desarrollo Vite la proxya y en producción services/web sirve este build.
export default defineConfig({
  plugins: [react(), tailwindcss()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
      '@contratos': fileURLToPath(new URL('../../packages/shared-types/src', import.meta.url)),
    },
  },
  server: {
    host: '127.0.0.1',
    port: 5174,
    proxy: { '/api': 'http://127.0.0.1:8081' },
    fs: { allow: ['..', '../..'] },
  },
  build: {
    outDir: 'dist',
    sourcemap: false,
    chunkSizeWarningLimit: 1200,
    rolldownOptions: {
      output: {
        // Dependencias grandes y estables en chunks propios: un cambio en la
        // app no invalida su caché, y el mapa y los gráficos solo bajan en las
        // páginas que los usan.
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
