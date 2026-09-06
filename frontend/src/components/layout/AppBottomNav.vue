<script setup lang="ts">
import { computed } from 'vue';
import { useRoute } from 'vue-router';
import * as Icons from '@element-plus/icons-vue';
import { PRIMARY_NAV_ITEMS } from '@/constants/navigation';
import { useXpbdSelector } from '@/composables/useXpbdSelector';

const route = useRoute();
const currentPath = computed(() => route.path);
const activeIndex = computed(() => (
  PRIMARY_NAV_ITEMS.findIndex((item) => currentPath.value.startsWith(item.to))
));
const { position: indicatorPosition } = useXpbdSelector(activeIndex);
const indicatorStyle = computed(() => ({
  '--active-index': String(Math.max(0, activeIndex.value)),
  '--indicator-position': String(indicatorPosition.value),
  '--indicator-opacity': activeIndex.value >= 0 ? '1' : '0',
}));

function getIcon(name: string) {
  return (Icons as Record<string, unknown>)[name];
}
</script>

<template>
  <nav class="app-bottom-nav" aria-label="移动端主导航" :style="indicatorStyle">
    <span class="app-bottom-nav__indicator" aria-hidden="true"></span>
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
  padding: 0.25rem 0.4rem;
  border: 1px solid color-mix(in srgb, var(--color-border-strong) 82%, rgba(255, 255, 255, 0.12));
  border-radius: 1.6rem;
  background:
    linear-gradient(135deg, color-mix(in srgb, var(--color-primary-soft) 72%, transparent), color-mix(in srgb, var(--color-accent) 8%, transparent)),
    color-mix(in srgb, var(--color-glass) 92%, transparent);
  backdrop-filter: blur(14px) saturate(1.08);
  -webkit-backdrop-filter: blur(14px) saturate(1.08);
  box-shadow:
    0 12px 28px color-mix(in srgb, var(--color-primary) 12%, transparent),
    inset 0 1px 0 rgba(255, 255, 255, 0.32);
  z-index: 45;
  overflow: hidden;
}

.app-bottom-nav__indicator {
  position: absolute;
  z-index: 0;
  top: 0.25rem;
  bottom: 0.25rem;
  left: 0.4rem;
  width: calc((100% - 0.8rem) / 5);
  border: 1px solid color-mix(in srgb, var(--color-primary) 24%, var(--color-border));
  border-radius: 1.15rem;
  background:
    linear-gradient(155deg, color-mix(in srgb, white 34%, transparent), transparent 58%),
    color-mix(in srgb, var(--color-primary-soft) 82%, var(--color-surface));
  box-shadow: 0 7px 18px color-mix(in srgb, var(--color-primary) 16%, transparent);
  opacity: var(--indicator-opacity);
  transform: translate3d(calc(var(--indicator-position) * 100%), 0, 0);
  transition: opacity var(--motion-fast) ease;
  will-change: transform;
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
  z-index: 1;
  transition: color var(--motion-fast) ease;
  position: relative;
  min-width: 0;
  min-height: 44px;
}

.app-bottom-nav__link.is-active {
  color: var(--color-primary);
}

.app-bottom-nav__icon {
  display: flex;
  align-items: center;
  justify-content: center;
  transition: transform var(--motion-base) var(--motion-spring),
    filter var(--motion-fast) ease;
}

.app-bottom-nav__link.is-active:not(.is-primary) .app-bottom-nav__icon {
  transform: translateY(-2px) scale(1.06);
}

.app-bottom-nav__link:active .app-bottom-nav__icon {
  transform: translateY(1px) scale(var(--motion-press-scale));
}

.app-bottom-nav__link.is-primary {
  transform: none;
}

.app-bottom-nav__link.is-primary .app-bottom-nav__icon {
  flex: 0 0 38px;
  width: 38px;
  height: 38px;
  aspect-ratio: 1;
  border-radius: 999px;
  color: white;
  background: var(--gradient-primary);
  box-shadow: 0 8px 18px color-mix(in srgb, var(--color-primary) 28%, transparent);
}

.app-bottom-nav__link.is-primary.is-active .app-bottom-nav__icon {
  background: linear-gradient(135deg, var(--color-accent), var(--color-secondary));
  transform: translateY(-2px) scale(1.04);
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

@media (prefers-reduced-motion: reduce) {
  .app-bottom-nav__indicator,
  .app-bottom-nav__icon {
    transition: none;
  }

  .app-bottom-nav__indicator {
    will-change: auto;
  }
}
</style>
