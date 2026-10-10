import { ElMessageBox } from 'element-plus';

let refreshPromptVisible = false;
let reloadPending = false;

function reloadAfterControllerChange() {
  if (reloadPending) {
    return;
  }
  reloadPending = true;
  navigator.serviceWorker.addEventListener('controllerchange', () => {
    window.location.reload();
  }, { once: true });
}

async function promptForRefresh(worker: ServiceWorker | null | undefined) {
  if (!worker || refreshPromptVisible) {
    return;
  }

  refreshPromptVisible = true;
  try {
    await ElMessageBox.confirm(
      '发现新版本，是否立即更新？',
      '新版本可用',
      {
        confirmButtonText: '立即更新',
        cancelButtonText: '稍后再说',
        type: 'info',
        distinguishCancelAndClose: true,
      },
    );
    reloadAfterControllerChange();
    worker.postMessage({ type: 'SKIP_WAITING' });
  } catch {
    // 用户选择稍后更新时保持当前版本，等下次打开或再次检测到更新。
  } finally {
    refreshPromptVisible = false;
  }
}

function bindUpdatePrompt(registration: ServiceWorkerRegistration) {
  void promptForRefresh(registration.waiting);

  registration.addEventListener('updatefound', () => {
    const installingWorker = registration.installing;
    if (!installingWorker) {
      return;
    }

    installingWorker.addEventListener('statechange', () => {
      if (installingWorker.state === 'installed' && navigator.serviceWorker.controller) {
        void promptForRefresh(registration.waiting);
      }
    });
  });
}

function requestUpdate(registration: ServiceWorkerRegistration) {
  void registration.update().catch((error) => {
    console.warn('Service Worker update check failed', error);
  });
}

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
    .then((registration) => {
      bindUpdatePrompt(registration);
      requestUpdate(registration);
      console.debug('Service Worker 注册成功');
    })
    .catch((error) => {
      console.warn('Service Worker 注册失败', error);
    });
}
