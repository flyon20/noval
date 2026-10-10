import { fileURLToPath, URL } from 'node:url';
import vue from '@vitejs/plugin-vue';
import { defineConfig } from 'vite';

async function resolvePwaPlugin() {
  try {
    const { VitePWA } = await import('vite-plugin-pwa');

    return VitePWA({
      injectRegister: false,
      registerType: 'prompt',
      manifest: {
        name: 'NOVAL 小说分析工作台',
        short_name: 'NOVAL',
        description: '趋势、分析、历史一站式 PWA 工作台',
        theme_color: '#f5efe4',
        background_color: '#ffffff',
        display: 'fullscreen',
        display_override: ['fullscreen', 'standalone'],
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
  const proxyTarget = process.env.VITE_PROXY_TARGET ?? 'http://127.0.0.1:8080';

  return {
    plugins: [vue(), ...(pwaPlugin ? [pwaPlugin] : [])],
    resolve: {
      alias: {
        '@': fileURLToPath(new URL('./src', import.meta.url)),
      },
    },
    server: {
      proxy: {
        '/api': {
          target: proxyTarget,
          changeOrigin: true,
        },
      },
    },
    test: {
      environment: 'jsdom',
      globals: true,
      setupFiles: './src/test/setup.ts',
      css: true,
      // Mounting a full view in jsdom costs ~2s on its own; under whole-suite
      // parallelism the slowest of those overshoot the 5s default and fail on the
      // clock alone. Give them headroom instead of trading away the coverage.
      testTimeout: 15000,
    },
  };
});
