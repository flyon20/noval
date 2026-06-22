import { computed, onBeforeUnmount, onMounted, ref } from 'vue';

export function useVisualViewportKeyboard() {
  const keyboardOffset = ref(0);

  function syncKeyboardOffset() {
    if (typeof window === 'undefined') {
      keyboardOffset.value = 0;
      return;
    }

    const visualViewport = window.visualViewport;
    if (!visualViewport) {
      keyboardOffset.value = 0;
      return;
    }

    const coveredHeight = window.innerHeight - visualViewport.height - visualViewport.offsetTop;
    keyboardOffset.value = Math.max(0, Math.round(coveredHeight));
  }

  onMounted(() => {
    syncKeyboardOffset();
    window.visualViewport?.addEventListener('resize', syncKeyboardOffset);
    window.visualViewport?.addEventListener('scroll', syncKeyboardOffset);
    window.addEventListener('resize', syncKeyboardOffset);
  });

  onBeforeUnmount(() => {
    window.visualViewport?.removeEventListener('resize', syncKeyboardOffset);
    window.visualViewport?.removeEventListener('scroll', syncKeyboardOffset);
    window.removeEventListener('resize', syncKeyboardOffset);
  });

  const keyboardStyle = computed(() => ({
    '--keyboard-offset': `${keyboardOffset.value}px`,
  }));

  return {
    keyboardOffset,
    keyboardStyle,
  };
}
