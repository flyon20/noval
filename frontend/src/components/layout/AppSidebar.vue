<script setup lang="ts">
import { computed } from 'vue';
import { PRIMARY_NAV_ITEMS } from '@/constants/navigation';

const props = defineProps<{
  roles: string[];
}>();

const configNavItems = computed(() => {
  const items = [
    { to: '/config/prompt', label: '提示词配置' },
  ];

  if (props.roles.includes('ADMIN')) {
    items.push({ to: '/config/system', label: '系统配置' });
  }

  return items;
});
</script>

<template>
  <aside class="app-sidebar">
    <div class="app-sidebar__brand">
      <p class="app-sidebar__eyebrow">NOVAL</p>
      <h1 class="app-sidebar__title">小说分析工作台</h1>
    </div>

    <nav class="app-sidebar__nav" aria-label="主导航">
      <RouterLink
        v-for="item in PRIMARY_NAV_ITEMS"
        :key="item.to"
        class="app-sidebar__link"
        :to="item.to"
        active-class="is-active"
      >
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

.app-sidebar__brand,
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
  font-size: 0.76rem;
  letter-spacing: 0.18em;
  text-transform: uppercase;
}

.app-sidebar__title {
  font-size: 1.95rem;
  line-height: 1.15;
}

.app-sidebar__section-title {
  color: var(--color-text-muted);
  font-size: 0.82rem;
  letter-spacing: 0.12em;
  text-transform: uppercase;
}

.app-sidebar__nav {
  display: grid;
  gap: 0.55rem;
}

.app-sidebar__link {
  display: flex;
  align-items: center;
  justify-content: flex-start;
  min-height: 46px;
  padding: 0.92rem 1rem;
  border: 1px solid transparent;
  border-radius: 1rem;
  color: var(--color-text);
  text-decoration: none;
  font-weight: 600;
  transition: transform 160ms ease, border-color 160ms ease, background 160ms ease;
}

.app-sidebar__link--secondary {
  background: rgba(36, 61, 54, 0.03);
}

.app-sidebar__link:hover {
  transform: translateX(2px);
  border-color: rgba(36, 61, 54, 0.12);
  background: rgba(255, 255, 255, 0.58);
}

.app-sidebar__link.is-active {
  border-color: rgba(199, 146, 92, 0.28);
  background: linear-gradient(135deg, rgba(199, 146, 92, 0.16), rgba(36, 61, 54, 0.08));
  box-shadow: var(--shadow-card);
}
</style>
