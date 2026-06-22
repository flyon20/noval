import { onBeforeUnmount, onMounted, ref, watch } from 'vue';

let nextDrawerId = 1;
const openDrawerStack: number[] = [];

function removeFromStack(id: number) {
  const index = openDrawerStack.lastIndexOf(id);
  if (index >= 0) {
    openDrawerStack.splice(index, 1);
  }
}

function isTopDrawer(id: number) {
  return openDrawerStack[openDrawerStack.length - 1] === id;
}

export function useMobileDrawerBack(options: {
  isOpen: () => boolean;
  close: () => void;
  hasInnerDetail?: () => boolean;
  backInnerDetail?: () => void;
  mobileWidth?: number;
  isMobile?: () => boolean;
}) {
  const drawerId = nextDrawerId++;
  const mobileWidth = options.mobileWidth ?? 960;
  const windowWidth = ref(typeof window === 'undefined' ? 1280 : window.innerWidth);
  const registered = ref(false);
  const pushedHistory = ref(false);

  function isMobile() {
    if (options.isMobile) {
      return options.isMobile();
    }
    return windowWidth.value < mobileWidth;
  }

  function syncWindowWidth() {
    windowWidth.value = window.innerWidth;
    if (!isMobile()) {
      unregisterDrawer({ consumeHistory: false });
    } else if (options.isOpen()) {
      registerDrawer();
    }
  }

  function registerDrawer() {
    if (registered.value || !isMobile()) {
      return;
    }
    removeFromStack(drawerId);
    openDrawerStack.push(drawerId);
    registered.value = true;
    if (typeof window !== 'undefined') {
      window.history.pushState({ ...(window.history.state ?? {}), mobileDrawerId: drawerId }, '');
      pushedHistory.value = true;
    }
  }

  function unregisterDrawer(options?: { consumeHistory?: boolean }) {
    if (!registered.value) {
      return;
    }
    removeFromStack(drawerId);
    registered.value = false;
    if (pushedHistory.value && options?.consumeHistory !== false && typeof window !== 'undefined') {
      window.history.back();
      pushedHistory.value = false;
    }
    if (options?.consumeHistory === false) {
      pushedHistory.value = false;
    }
  }

  function handlePopState(event: PopStateEvent) {
    if (!registered.value || !options.isOpen() || !isTopDrawer(drawerId)) {
      return;
    }
    const stateDrawerId = typeof event.state === 'object' && event.state !== null
      ? (event.state as { mobileDrawerId?: number }).mobileDrawerId
      : undefined;
    if (stateDrawerId === drawerId) {
      if (options.hasInnerDetail?.()) {
        options.backInnerDetail?.();
      }
      return;
    }
    if (options.hasInnerDetail?.()) {
      options.backInnerDetail?.();
      return;
    }
    unregisterDrawer({ consumeHistory: false });
    options.close();
  }

  onMounted(() => {
    syncWindowWidth();
    window.addEventListener('resize', syncWindowWidth);
    window.addEventListener('popstate', handlePopState);
  });

  onBeforeUnmount(() => {
    unregisterDrawer({ consumeHistory: false });
    window.removeEventListener('resize', syncWindowWidth);
    window.removeEventListener('popstate', handlePopState);
  });

  watch(
    () => options.isOpen(),
    (isOpen) => {
      if (isOpen) {
        registerDrawer();
      } else {
        unregisterDrawer();
      }
    },
  );

  return {
    isMobile,
    unregisterDrawer,
  };
}
