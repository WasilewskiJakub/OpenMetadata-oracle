import react from '@vitejs/plugin-react';
import { defineConfig } from 'vite';

const backendTarget = process.env.VITE_BACKEND_TARGET ?? 'http://127.0.0.1:8080';

export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      '/api': {
        target: backendTarget,
        changeOrigin: true,
      },
    },
  },
});
