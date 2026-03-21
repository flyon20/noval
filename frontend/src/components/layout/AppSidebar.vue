<script setup lang="ts">
import { PRIMARY_NAV_ITEMS } from '@/constants/navigation';
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
  </aside>
</template>

<style scoped lang="scss">
.app-sidebar {
  display: flex;
  flex-direction: column;
  gap: 1.5rem;
  padding: 1.5rem;
}

.app-sidebar__brand {
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

.app-sidebar__subtitle {
  margin: 0;
  color: var(--color-text-muted);
  line-height: 1.7;
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

.app-sidebar__link:hover,
.app-sidebar__link.is-active {
  background: rgba(35, 65, 58, 0.08);
  border-color: rgba(35, 65, 58, 0.14);
}
</style>
