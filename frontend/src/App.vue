<script setup lang="ts">
import { onMounted, onUnmounted } from 'vue';
import { RouterView } from 'vue-router';
import PwaInstallPrompt from '@/components/pwa/PwaInstallPrompt.vue';
import { bootstrapTheme } from '@/lib/theme';

let disposeThemeBootstrap: (() => void) | undefined;

onMounted(() => {
  disposeThemeBootstrap = bootstrapTheme();
});

onUnmounted(() => {
  disposeThemeBootstrap?.();
});
</script>

<template>
  <RouterView />
  <PwaInstallPrompt />
</template>
