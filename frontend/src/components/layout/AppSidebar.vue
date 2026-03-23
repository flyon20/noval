<script setup lang="ts">
import { computed } from 'vue';
import * as Icons from '@element-plus/icons-vue';
import { PRIMARY_NAV_ITEMS } from '@/constants/navigation';

const props = defineProps<{
  roles: string[];
}>();

const configNavItems = computed(() => {
  const items = [
    { to: '/config/prompt', label: '提示词配置', icon: 'EditPen' },
  ];

  if (props.roles.includes('ADMIN')) {
    items.push({ to: '/config/system', label: '系统配置', icon: 'Setting' });
  }

  return items;
});

function getIcon(name: string) {
  return (Icons as Record<string, unknown>)[name];
}
</script>

<template>
  <aside class="app-sidebar">
    <div class="app-sidebar__brand">
      <div class="app-sidebar__logo">
        <svg width="28" height="28" viewBox="0 0 28 28" fill="none" xmlns="http://www.w3.org/2000/svg">
          <rect x="3" y="4" width="16" height="20" rx="2" fill="rgba(199,146,92,0.18)" stroke="var(--color-accent)" stroke-width="1.5"/>
          <rect x="7" y="4" width="16" height="20" rx="2" fill="rgba(36,61,54,0.08)" stroke="var(--color-primary)" stroke-width="1.5"/>
          <line x1="10" y1="10" x2="20" y2="10" stroke="var(--color-primary)" stroke-width="1.2" stroke-linecap="round"/>
          <line x1="10" y1="13.5" x2="20" y2="13.5" stroke="var(--color-primary)" stroke-width="1.2" stroke-linecap="round"/>
          <line x1="10" y1="17" x2="16" y2="17" stroke="var(--color-accent)" stroke-width="1.2" stroke-linecap="round"/>
        </svg>
      </div>
      <div>
        <p class="app-sidebar__eyebrow">NOVAL STUDIO</p>
        <h1 class="app-sidebar__title">小说分析工作台</h1>
      </div>
    </div>

    <nav class="app-sidebar__nav" aria-label="主导航">
      <RouterLink
        v-for="item in PRIMARY_NAV_ITEMS"
        :key="item.to"
        class="app-sidebar__link"
        :to="item.to"
        active-class="is-active"
      >
        <el-icon :size="18" class="app-sidebar__link-icon"><component :is="getIcon(item.icon)" /></el-icon>
        <span>{{ item.label }}</span>
      </RouterLink>
    </nav>

    <section class="app-sidebar__section">
      <p class="app-sidebar__section-title">配置中心</p>
      <nav class="app-sidebar__nav" aria-label="配置导航">
        <RouterLink
          v-for="item in configNavItems"
          :key="item.to"
          class="app-sidebar__link app-sidebar__link--secondary"
          :to="item.to"
          active-class="is-active"
        >
          <el-icon :size="16" class="app-sidebar__link-icon"><component :is="getIcon(item.icon)" /></el-icon>
          <span>{{ item.label }}</span>
        </RouterLink>
      </nav>
    </section>
  </aside>
</template>

<style scoped lang="scss">
.app-sidebar {
  display: flex;
  flex-direction: column;
  gap: 1.65rem;
  padding: 1.6rem;
  background:
    linear-gradient(180deg, rgba(255, 255, 255, 0.42), transparent),
    rgba(255, 250, 244, 0.7);
}

.app-sidebar__brand {
  display: flex;
  align-items: center;
  gap: 0.75rem;
}

.app-sidebar__logo {
  flex-shrink: 0;
  display: flex;
  align-items: center;
}

.app-sidebar__brand > div:last-child {
  display: grid;
  gap: 0.2rem;
}

.app-sidebar__section {
  display: grid;
  gap: 0.55rem;
}

.app-sidebar__eyebrow,
.app-sidebar__title,
.app-sidebar__section-title {
  margin: 0;
}

.app-sidebar__eyebrow {
  color: var(--color-accent-strong);
  font-size: 0.68rem;
  letter-spacing: 0.16em;
  text-transform: uppercase;
  font-weight: 600;
}

.app-sidebar__title {
  font-size: 1rem;
  font-weight: 700;
  letter-spacing: 0.01em;
  font-family: var(--font-heading);
}

.app-sidebar__section-title {
  color: var(--color-text-muted);
  font-size: 0.78rem;
  letter-spacing: 0.12em;
  text-transform: uppercase;
  padding-left: 0.5rem;
}

.app-sidebar__nav {
  display: grid;
  gap: 0.3rem;
}

.app-sidebar__link {
  display: flex;
  align-items: center;
  gap: 0.65rem;
  min-height: 44px;
  padding: 0.7rem 1rem;
  border: 1px solid transparent;
  border-radius: 0.85rem;
  color: var(--color-text-muted);
  text-decoration: none;
  font-weight: 600;
  font-size: 0.95rem;
  transition: color 160ms ease, background 160ms ease, border-color 160ms ease;
  position: relative;
}

.app-sidebar__link-icon {
  flex-shrink: 0;
  transition: color 160ms ease;
}

.app-sidebar__link--secondary {
  font-size: 0.875rem;
  min-height: 40px;
}

.app-sidebar__link:hover {
  color: var(--color-text);
  border-color: rgba(36, 61, 54, 0.1);
  background: rgba(255, 255, 255, 0.55);
}

.app-sidebar__link.is-active {
  color: var(--color-primary);
  border-color: rgba(199, 146, 92, 0.22);
  background: linear-gradient(135deg, rgba(199, 146, 92, 0.12), rgba(36, 61, 54, 0.06));
  box-shadow: var(--shadow-card);
}

.app-sidebar__link.is-active::before {
  content: '';
  position: absolute;
  left: 0;
  top: 25%;
  bottom: 25%;
  width: 3px;
  border-radius: 0 3px 3px 0;
  background: var(--color-accent);
}
</style>
