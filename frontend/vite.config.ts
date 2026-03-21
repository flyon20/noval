import { fileURLToPath, URL } from 'node:url';
import vue from '@vitejs/plugin-vue';
import { defineConfig } from 'vite';

async function resolvePwaPlugin() {
  try {
    const { VitePWA } = await import('vite-plugin-pwa');

    return VitePWA({
      registerType: 'autoUpdate',
      manifest: {
        name: 'NOVAL 小说分析工作台',
        short_name: 'NOVAL',
        description: '趋势、分析、历史一站式 PWA 工作台',
        theme_color: '#f5efe4',
        background_color: '#ffffff',
        display: 'standalone',
        start_url: '/',
        icons: [
          {
            src: '/pwa-192.png',
            sizes: '192x192',
            type: 'image/png',
          },
          {
            src: '/pwa-512.png',
            sizes: '512x512',
            type: 'image/png',
          },
        ],
      },
      workbox: {
        runtimeCaching: [
          {
            urlPattern: /^\/api\/data\/visual/,
            handler: 'NetworkFirst',
            options: {
              cacheName: 'visual-data-cache',
              expiration: {
                maxEntries: 5,
                maxAgeSeconds: 3600,
              },
            },
          },
        ],
      },
    });
  } catch {
    return null;
  }
}

export default defineConfig(async () => {
  const pwaPlugin = await resolvePwaPlugin();

  return {
    plugins: [vue(), ...(pwaPlugin ? [pwaPlugin] : [])],
    resolve: {
      alias: {
        '@': fileURLToPath(new URL('./src', import.meta.url)),
      },
    },
    test: {
      environment: 'jsdom',
      globals: true,
      setupFiles: './src/test/setup.ts',
      css: true,
    },
  };
});
