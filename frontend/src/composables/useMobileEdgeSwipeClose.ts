import { onBeforeUnmount, onMounted, ref } from 'vue';

export function useMobileEdgeSwipeClose(onClose: () => void, options: {
  edgeThreshold?: number;
  horizontalThreshold?: number;
  verticalThreshold?: number;
  mobileWidth?: number;
} = {}) {
  const edgeThreshold = options.edgeThreshold ?? 32;
  const horizontalThreshold = options.horizontalThreshold ?? 72;
  const verticalThreshold = options.verticalThreshold ?? 52;
  const mobileWidth = options.mobileWidth ?? 960;
  const windowWidth = ref(typeof window === 'undefined' ? 1280 : window.innerWidth);
  const touchStart = ref<{ x: number; y: number } | null>(null);
  const pointerStart = ref<{ x: number; y: number } | null>(null);

  function isMobile() {
    return windowWidth.value < mobileWidth;
  }

  function syncWindowWidth() {
    windowWidth.value = window.innerWidth;
    touchStart.value = null;
    pointerStart.value = null;
  }

  function shouldCloseBySwipe(start: { x: number; y: number }, end: { x: number; y: number }) {
    const deltaX = end.x - start.x;
    const deltaY = Math.abs(end.y - start.y);
    return deltaX > horizontalThreshold && deltaY < verticalThreshold;
  }

  function onTouchStart(event: TouchEvent) {
    if (!isMobile() || event.touches.length !== 1) {
      return;
    }
    const touch = event.touches[0];
    if (touch.clientX > edgeThreshold) {
      touchStart.value = null;
      return;
    }
    touchStart.value = { x: touch.clientX, y: touch.clientY };
  }

  function onTouchEnd(event: TouchEvent) {
    if (!isMobile() || !touchStart.value || event.changedTouches.length !== 1) {
      touchStart.value = null;
      return;
    }
    const touch = event.changedTouches[0];
    const shouldClose = shouldCloseBySwipe(touchStart.value, { x: touch.clientX, y: touch.clientY });
    touchStart.value = null;
    if (shouldClose) {
      onClose();
    }
  }

  function onPointerStart(event: PointerEvent) {
    if (!isMobile() || event.clientX > edgeThreshold) {
      pointerStart.value = null;
      return;
    }
    pointerStart.value = { x: event.clientX, y: event.clientY };
  }

  function onPointerEnd(event: PointerEvent) {
    if (!isMobile() || !pointerStart.value) {
      pointerStart.value = null;
      return;
    }
    const shouldClose = shouldCloseBySwipe(pointerStart.value, { x: event.clientX, y: event.clientY });
    pointerStart.value = null;
    if (shouldClose) {
      onClose();
    }
  }

  onMounted(() => {
    window.addEventListener('resize', syncWindowWidth);
  });

  onBeforeUnmount(() => {
    window.removeEventListener('resize', syncWindowWidth);
  });

  return {
    windowWidth,
    isMobile,
    onTouchStart,
    onTouchEnd,
    onPointerStart,
    onPointerEnd,
  };
}
