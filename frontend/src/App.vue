<script setup lang="ts">
import { onMounted, onUnmounted } from 'vue';
import { RouterView } from 'vue-router';
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
</template>
