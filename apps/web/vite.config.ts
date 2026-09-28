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
  build: { outDir: 'dist', sourcemap: false },
});
