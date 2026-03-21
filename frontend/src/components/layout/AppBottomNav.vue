<script setup lang="ts">
import { computed, inject } from 'vue';
import { routeLocationKey } from 'vue-router';
import { PRIMARY_NAV_ITEMS } from '@/constants/navigation';

const injectedRoute = inject(routeLocationKey, null);
const currentPath = computed(() => injectedRoute?.path ?? '');
</script>

<template>
  <nav class="app-bottom-nav">
    <RouterLink
      v-for="item in PRIMARY_NAV_ITEMS"
      :key="item.to"
      class="app-bottom-nav__link"
      :to="item.to"
      :class="{ 'is-active': currentPath === item.to }"
    >
      <span>{{ item.label }}</span>
    </RouterLink>
  </nav>
</template>

<style scoped lang="scss">
.app-bottom-nav {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  position: fixed;
  bottom: 0;
  left: 0;
  right: 0;
  border-top: 1px solid rgba(35, 65, 58, 0.12);
  background: rgba(255, 255, 255, 0.96);
  padding: 0.25rem 0;
  z-index: 30;
}

.app-bottom-nav__link {
  text-align: center;
  color: var(--color-text);
  font-size: 0.85rem;
  padding: 0.4rem 0;
  font-weight: 600;
}

.app-bottom-nav__link.is-active {
  color: var(--color-primary);
}
</style>
