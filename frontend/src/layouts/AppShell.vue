<script setup lang="ts">
import AppHeader from '@/components/layout/AppHeader.vue';
import AppSidebar from '@/components/layout/AppSidebar.vue';
import AppBottomNav from '@/components/layout/AppBottomNav.vue';

defineProps<{
  username: string;
  roles: string[];
}>();

const emit = defineEmits<{
  logout: [];
}>();
</script>

<template>
  <div class="app-shell">
    <div class="app-shell__backdrop"></div>

    <div class="app-shell__sidebar">
      <AppSidebar :roles="roles" />
    </div>

    <div class="app-shell__surface">
      <AppHeader :roles="roles" :username="username" @logout="emit('logout')" />
      <main class="app-shell__content">
        <slot />
      </main>
      <AppBottomNav class="app-shell__mobile-nav" />
    </div>
  </div>
</template>

<style scoped lang="scss">
.app-shell {
  position: relative;
  display: grid;
  grid-template-columns: minmax(290px, 330px) 1fr;
  gap: 1.5rem;
  min-height: 100vh;
  max-width: 100%;
  padding: 1.35rem;
  background:
    radial-gradient(circle at top left, rgba(199, 146, 92, 0.15), transparent 24%),
    radial-gradient(circle at bottom right, rgba(36, 61, 54, 0.1), transparent 20%),
    linear-gradient(180deg, var(--color-bg), var(--color-bg-secondary));
}

.app-shell__backdrop {
  position: absolute;
  inset: 0;
  pointer-events: none;
  background:
    linear-gradient(130deg, rgba(255, 255, 255, 0.3), transparent 42%),
    radial-gradient(circle at 18% 12%, rgba(255, 255, 255, 0.32), transparent 26%);
}

.app-shell__sidebar,
.app-shell__surface {
  position: relative;
  min-width: 0;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-xl);
  background: var(--color-surface);
  box-shadow: var(--shadow-soft);
}

.app-shell__sidebar {
  position: sticky;
  top: 1.35rem;
  align-self: start;
  overflow: hidden;
  backdrop-filter: none;
  -webkit-backdrop-filter: none;
  background: color-mix(in srgb, var(--color-surface-strong) 98%, transparent);
}

.app-shell__surface {
  display: flex;
  flex-direction: column;
  overflow: hidden;
  max-width: 100%;
  backdrop-filter: none;
  -webkit-backdrop-filter: none;
}

.app-shell__content {
  padding: 1.5rem;
  flex: 1;
  min-width: 0;
}

.app-shell__surface :deep(.rank-page__hero),
.app-shell__surface :deep(.rank-page__panel),
.app-shell__surface :deep(.rank-page__hero-badge),
.app-shell__surface :deep(.rank-page__page-size),
.app-shell__surface :deep(.rank-page__snapshot-card),
.app-shell__surface :deep(.rank-page__mobile-update),
.app-shell__surface :deep(.rank-page__item),
.app-shell__surface :deep(.trend-context),
.app-shell__surface :deep(.trend-page__toolbar),
.app-shell__surface :deep(.trend-page__support-card),
.app-shell__surface :deep(.trend-page__visual-header),
.app-shell__surface :deep(.trend-chart-card),
.app-shell__surface :deep(.trend-summary__card),
.app-shell__surface :deep(.trend-comparison-list),
.app-shell__surface :deep(.trend-result-preview__card),
.app-shell__surface :deep(.analysis-context),
.app-shell__surface :deep(.analysis-result-card) {
  backdrop-filter: none;
  -webkit-backdrop-filter: none;
  background:
    linear-gradient(
      180deg,
      color-mix(in srgb, var(--color-surface-strong) 98%, transparent),
      color-mix(in srgb, var(--color-surface) 94%, transparent)
    );
  box-shadow: 0 12px 28px rgba(18, 25, 58, 0.08);
}

.app-shell__surface :deep(.rank-page__item:hover),
.app-shell__surface :deep(.analysis-result-card:hover) {
  box-shadow: 0 14px 30px rgba(18, 25, 58, 0.1);
}

/* Tablet breakpoint */
@media (max-width: 980px) and (min-width: 769px) {
  .app-shell {
    grid-template-columns: minmax(240px, 280px) 1fr;
    gap: 1rem;
    padding: 1rem;
  }

  .app-shell__sidebar {
    position: static;
  }
}

/* Mobile breakpoint */
@media (max-width: 768px) {
  .app-shell {
    grid-template-columns: 1fr;
    gap: 0;
    padding: 0;
    min-height: 100dvh;
    background: var(--color-bg);
    overflow-x: clip;
  }

  .app-shell__backdrop {
    display: none;
  }

  .app-shell__sidebar {
    display: none;
  }

  .app-shell__surface {
    border: none;
    border-radius: 0;
    box-shadow: none;
    background: transparent;
    backdrop-filter: none;
    min-height: 100dvh;
    overflow: visible;
  }

  .app-shell__content {
    padding:
      calc(0.875rem + 56px)
      0.875rem
      calc(var(--bottom-nav-height) + env(safe-area-inset-bottom, 0px) + 1.5rem);
    width: 100%;
    max-width: 100%;
    overflow-x: clip;
  }
}
</style>
