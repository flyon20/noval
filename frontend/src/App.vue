<script setup lang="ts">
import { RouterView } from 'vue-router';

type AppTheme = 'light' | 'dark';
const THEME_STORAGE_KEY = 'preferred-theme';

const applyTheme = (theme: AppTheme): void => {
  if (typeof document === 'undefined') {
    return;
  }

  document.documentElement.dataset.theme = theme;
};

const getStoredTheme = (): AppTheme | null => {
  if (typeof window === 'undefined') {
    return null;
  }

  try {
    const stored = window.localStorage.getItem(THEME_STORAGE_KEY);
    if (stored === 'light' || stored === 'dark') {
      return stored;
    }
  } catch {
    // ignore storage access issues
  }

  return null;
};

const prefersDarkQuery = typeof window !== 'undefined' && typeof window.matchMedia === 'function'
  ? window.matchMedia('(prefers-color-scheme: dark)')
  : null;

const systemTheme: AppTheme = prefersDarkQuery?.matches ? 'dark' : 'light';
const storedTheme = getStoredTheme();
const bootTheme = storedTheme ?? systemTheme;
applyTheme(bootTheme);

if (!storedTheme && prefersDarkQuery) {
  const handleSchemeChange = (event: MediaQueryListEvent | MediaQueryList) => {
    applyTheme(event.matches ? 'dark' : 'light');
  };

  if ('addEventListener' in prefersDarkQuery) {
    prefersDarkQuery.addEventListener('change', handleSchemeChange);
  } else {
    prefersDarkQuery.addListener(handleSchemeChange);
  }
}
</script>

<template>
  <RouterView />
</template>
