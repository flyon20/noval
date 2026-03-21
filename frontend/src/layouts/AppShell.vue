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
    <div class="app-shell__sidebar">
      <AppSidebar />
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
  display: grid;
  grid-template-columns: minmax(280px, 320px) 1fr;
  gap: 1.5rem;
  min-height: 100vh;
  padding: 1.25rem;
}

.app-shell__sidebar,
.app-shell__surface {
  min-width: 0;
  background: var(--color-surface);
  border: 1px solid var(--color-border);
  border-radius: 1.5rem;
  box-shadow: var(--shadow-soft);
}

.app-shell__sidebar {
  position: sticky;
  top: 1.25rem;
  align-self: start;
}

.app-shell__surface {
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.app-shell__content {
  padding: 1.5rem;
}

@media (max-width: 980px) {
  .app-shell {
    grid-template-columns: 1fr;
  }

  .app-shell__sidebar {
    position: static;
  }
}
</style>
