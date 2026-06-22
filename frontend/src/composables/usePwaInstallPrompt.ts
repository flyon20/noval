import { computed, onBeforeUnmount, onMounted, ref } from 'vue';

const APP_DISPLAY_MODES = ['standalone', 'fullscreen', 'minimal-ui'];
const MOBILE_WIDTH = 768;

function isAppDisplayMode() {
  if (typeof window === 'undefined') {
    return false;
  }

  return APP_DISPLAY_MODES.some((mode) => window.matchMedia?.(`(display-mode: ${mode})`)?.matches)
    || (window.navigator as Navigator & { standalone?: boolean }).standalone === true;
}

export function usePwaInstallPrompt() {
  const promptEvent = ref<BeforeInstallPromptEvent | null>(null);
  const installed = ref(isAppDisplayMode());
  const dismissed = ref(false);
  const windowWidth = ref(typeof window === 'undefined' ? 1280 : window.innerWidth);

  const isMobile = computed(() => windowWidth.value <= MOBILE_WIDTH);
  const canInstall = computed(() => !!promptEvent.value);
  const manualGuideVisible = computed(() => isMobile.value && !installed.value && !promptEvent.value);
  const visible = computed(() => !dismissed.value && !installed.value && (canInstall.value || manualGuideVisible.value));
  const platformHint = computed(() => {
    if (typeof navigator === 'undefined') {
      return 'generic';
    }
    const userAgent = navigator.userAgent.toLowerCase();
    if (/iphone|ipad|ipod/.test(userAgent)) {
      return 'ios';
    }
    if (/android/.test(userAgent)) {
      return 'android';
    }
    return 'generic';
  });

  function syncEnvironment() {
    windowWidth.value = window.innerWidth;
    installed.value = isAppDisplayMode();
  }

  async function install() {
    if (!promptEvent.value) {
      return false;
    }

    promptEvent.value.prompt();
    const choice = await promptEvent.value.userChoice;
    promptEvent.value = null;
    installed.value = choice.outcome === 'accepted' || isAppDisplayMode();
    dismissed.value = !installed.value;
    return choice.outcome === 'accepted';
  }

  function handleBeforeInstallPrompt(event: Event) {
    event.preventDefault();
    promptEvent.value = event as BeforeInstallPromptEvent;
    dismissed.value = false;
  }

  function handleAppInstalled() {
    installed.value = true;
    promptEvent.value = null;
  }

  onMounted(() => {
    syncEnvironment();
    window.addEventListener('beforeinstallprompt', handleBeforeInstallPrompt as EventListener);
    window.addEventListener('appinstalled', handleAppInstalled);
    window.addEventListener('resize', syncEnvironment);
  });

  onBeforeUnmount(() => {
    window.removeEventListener('beforeinstallprompt', handleBeforeInstallPrompt as EventListener);
    window.removeEventListener('appinstalled', handleAppInstalled);
    window.removeEventListener('resize', syncEnvironment);
  });

  return {
    visible,
    installed: computed(() => installed.value),
    canInstall,
    manualGuideVisible,
    platformHint,
    install,
    dismiss() {
      dismissed.value = true;
    },
  };
}
