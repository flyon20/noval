export function registerServiceWorker() {
  if (
    !import.meta.env.PROD ||
    typeof navigator === 'undefined' ||
    !('serviceWorker' in navigator) ||
    import.meta.env.VITE_DISABLE_SW === 'true'
  ) {
    return;
  }

  navigator.serviceWorker
    .register('/sw.js')
    .then(() => {
      console.debug('Service Worker 注册成功');
    })
    .catch((error) => {
      console.warn('Service Worker 注册失败', error);
    });
}
