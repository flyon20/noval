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
      <p class="app-sidebar__subtitle">
        把扫榜、单书分析、趋势对比和历史回看串成一条可复盘的分析链路。
      </p>
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
  gap: 1.5rem;
  padding: 1.5rem;
}

.app-sidebar__brand,
.app-sidebar__section {
  display: grid;
  gap: 0.5rem;
}

.app-sidebar__eyebrow {
  margin: 0;
  color: var(--color-accent);
  font-size: 0.75rem;
  letter-spacing: 0.2em;
}

.app-sidebar__title {
  margin: 0;
  font-size: 1.8rem;
  line-height: 1.1;
}

.app-sidebar__subtitle,
.app-sidebar__section-title {
  margin: 0;
  color: var(--color-text-muted);
  line-height: 1.7;
}

.app-sidebar__section-title {
  font-size: 0.84rem;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.app-sidebar__nav {
  display: grid;
  gap: 0.5rem;
}

.app-sidebar__link {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 0.75rem;
  min-height: 44px;
  padding: 0.9rem;
  border: 1px solid transparent;
  border-radius: 1rem;
  color: var(--color-text);
  text-decoration: none;
  font-weight: 600;
  transition: 180ms ease;
}

.app-sidebar__link--secondary {
  justify-content: flex-start;
  background: rgba(35, 65, 58, 0.03);
}

.app-sidebar__link:hover,
.app-sidebar__link.is-active {
  background: rgba(35, 65, 58, 0.08);
  border-color: rgba(35, 65, 58, 0.14);
}
</style>
