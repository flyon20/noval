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
      :class="{ 'is-active': currentPath.startsWith(item.to) }"
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
  grid-template-columns: repeat(4, 1fr);
  position: fixed;
  bottom: 0;
  left: 0;
  right: 0;
  height: var(--bottom-nav-height);
  padding-bottom: env(safe-area-inset-bottom, 0px);
  border-top: 1px solid rgba(35, 65, 58, 0.12);
  background: rgba(255, 252, 248, 0.97);
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
  z-index: 30;
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
}

.app-bottom-nav__link::after {
  content: '';
  position: absolute;
  top: 0;
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

.app-bottom-nav__label {
  font-size: 11px;
  font-weight: 500;
  line-height: 1;
}
</style>
