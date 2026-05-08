<script setup lang="ts">
import { computed } from 'vue';
import { useRoute } from 'vue-router';
import * as Icons from '@element-plus/icons-vue';
import { PRIMARY_NAV_ITEMS } from '@/constants/navigation';

const route = useRoute();
const currentPath = computed(() => route.path);

function getIcon(name: string) {
  return (Icons as Record<string, unknown>)[name];
}
</script>

<template>
  <nav class="app-bottom-nav">
    <RouterLink
      v-for="item in PRIMARY_NAV_ITEMS"
      :key="item.to"
      class="app-bottom-nav__link"
      :to="item.to"
      :class="{ 'is-active': currentPath.startsWith(item.to), 'is-primary': item.primary }"
    >
      <span class="app-bottom-nav__icon">
        <el-icon :size="22"><component :is="getIcon(item.icon)" /></el-icon>
      </span>
      <span class="app-bottom-nav__label">{{ item.label }}</span>
    </RouterLink>
  </nav>
</template>

<style scoped lang="scss">
.app-bottom-nav {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  position: fixed;
  bottom: calc(env(safe-area-inset-bottom, 0px) + 10px);
  left: 12px;
  right: 12px;
  height: var(--bottom-nav-height);
  padding: 0.35rem 0.4rem calc(0.35rem + env(safe-area-inset-bottom, 0px));
  border: 1px solid color-mix(in srgb, var(--color-border-strong) 82%, rgba(255, 255, 255, 0.12));
  border-radius: 1.6rem;
  background:
    linear-gradient(180deg, rgba(255, 255, 255, 0.24), rgba(255, 255, 255, 0.06)),
    color-mix(in srgb, var(--color-glass) 92%, transparent);
  backdrop-filter: blur(14px) saturate(1.08);
  -webkit-backdrop-filter: blur(14px) saturate(1.08);
  box-shadow:
    0 12px 28px rgba(15, 23, 42, 0.12),
    inset 0 1px 0 rgba(255, 255, 255, 0.32);
  z-index: 45;
  overflow: hidden;
}

.app-bottom-nav__link {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 3px;
  color: var(--color-text-muted);
  text-decoration: none;
  font-size: 0;
  transition: color 150ms ease;
  position: relative;
  min-width: 0;
  min-height: 44px;
}

.app-bottom-nav__link::after {
  content: '';
  position: absolute;
  top: 2px;
  left: 50%;
  transform: translateX(-50%) scaleX(0);
  width: 24px;
  height: 3px;
  border-radius: 0 0 3px 3px;
  background: var(--color-primary);
  transition: transform 150ms ease;
}

.app-bottom-nav__link.is-active {
  color: var(--color-primary);
}

.app-bottom-nav__link.is-active::after {
  transform: translateX(-50%) scaleX(1);
}

.app-bottom-nav__icon {
  display: flex;
  align-items: center;
  justify-content: center;
}

.app-bottom-nav__link.is-primary {
  transform: translateY(-7px);
}

.app-bottom-nav__link.is-primary .app-bottom-nav__icon {
  width: 46px;
  height: 46px;
  border-radius: 999px;
  color: white;
  background: var(--color-primary);
  box-shadow: 0 8px 18px rgba(36, 61, 54, 0.24);
}

.app-bottom-nav__link.is-primary::after {
  display: none;
}

.app-bottom-nav__link.is-primary.is-active .app-bottom-nav__icon {
  background: var(--color-accent);
}

.app-bottom-nav__label {
  font-size: 11px;
  font-weight: 500;
  line-height: 1;
}

@media (min-width: 769px) {
  .app-bottom-nav {
    display: none;
  }
}
</style>
