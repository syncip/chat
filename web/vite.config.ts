import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// Reproduzierbarer Build: keine Zeitstempel, feste Dateinamen mit Inhalts-Hash.
export default defineConfig({
  plugins: [react()],
  build: { target: 'es2022', sourcemap: false, assetsInlineLimit: 0, modulePreload: { polyfill: false } },
  server: { proxy: { '/v1': { target: 'http://localhost:8080', ws: true }, '/.well-known': 'http://localhost:8080' } },
  test: { environment: 'node' },
});
