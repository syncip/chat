import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import { readFileSync } from 'node:fs';

const pkg = JSON.parse(readFileSync(new URL('./package.json', import.meta.url), 'utf8')) as { version: string };

// Reproduzierbarer Build: keine Zeitstempel, feste Dateinamen mit Inhalts-Hash.
export default defineConfig({
  plugins: [react()],
  define: { __APP_VERSION__: JSON.stringify(pkg.version) },
  build: { target: 'es2022', sourcemap: false, assetsInlineLimit: 0, modulePreload: { polyfill: false } },
  server: { proxy: { '/v1': { target: 'http://localhost:8080', ws: true }, '/.well-known': 'http://localhost:8080' } },
  test: { environment: 'node' },
});
