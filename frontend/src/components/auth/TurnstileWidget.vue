<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';

type TurnstileRenderOptions = {
  sitekey: string;
  callback?: (token: string) => void;
  'expired-callback'?: () => void;
  'error-callback'?: () => void;
};

type TurnstileApi = {
  render: (container: HTMLElement, options: TurnstileRenderOptions) => string;
  reset: (widgetId?: string) => void;
  remove?: (widgetId?: string) => void;
};

const props = defineProps<{
  siteKey: string;
}>();

const emit = defineEmits<{
  verified: [token: string];
  expired: [];
  error: [];
}>();

const containerRef = ref<HTMLElement | null>(null);
const widgetId = ref<string | null>(null);

declare global {
  interface Window {
    turnstile?: TurnstileApi;
    __novalTurnstileScriptPromise__?: Promise<void>;
  }
}

function getTurnstileApi() {
  return window.turnstile;
}

function loadTurnstileScript() {
  if (window.turnstile) {
    return Promise.resolve();
  }
  if (window.__novalTurnstileScriptPromise__) {
    return window.__novalTurnstileScriptPromise__;
  }
  window.__novalTurnstileScriptPromise__ = new Promise((resolve, reject) => {
    const existing = document.querySelector<HTMLScriptElement>('script[data-turnstile-script="true"]');
    if (existing) {
      if (window.turnstile) {
        resolve();
        return;
      }
      existing.addEventListener('load', () => resolve(), { once: true });
      existing.addEventListener('error', () => reject(new Error('turnstile script load failed')), { once: true });
      return;
    }

    const script = document.createElement('script');
    script.src = 'https://challenges.cloudflare.com/turnstile/v0/api.js?render=explicit';
    script.async = true;
    script.defer = true;
    script.dataset.turnstileScript = 'true';
    script.addEventListener('load', () => resolve(), { once: true });
    script.addEventListener('error', () => reject(new Error('turnstile script load failed')), { once: true });
    document.head.appendChild(script);
  });
  return window.__novalTurnstileScriptPromise__;
}

async function renderWidget() {
  await nextTick();
  if (!props.siteKey || !containerRef.value) {
    return;
  }
  try {
    await loadTurnstileScript();
  } catch {
    emit('error');
    return;
  }

  const api = getTurnstileApi();
  if (!api || !containerRef.value) {
    emit('error');
    return;
  }

  if (widgetId.value) {
    api.reset(widgetId.value);
    return;
  }

  widgetId.value = api.render(containerRef.value, {
    sitekey: props.siteKey,
    callback: (token) => emit('verified', token),
    'expired-callback': () => emit('expired'),
    'error-callback': () => emit('error'),
  });
}

function reset() {
  const api = getTurnstileApi();
  if (api && widgetId.value) {
    api.reset(widgetId.value);
  }
}

defineExpose({ reset });

watch(() => props.siteKey, () => {
  widgetId.value = null;
  void renderWidget();
});

onMounted(() => {
  void renderWidget();
});

onBeforeUnmount(() => {
  const api = getTurnstileApi();
  if (api && widgetId.value && typeof api.remove === 'function') {
    api.remove(widgetId.value);
  }
});
</script>

<template>
  <div class="turnstile-widget" data-test="turnstile-widget">
    <div ref="containerRef" />
  </div>
</template>
