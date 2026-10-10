<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue';
import { useRoute } from 'vue-router';
import * as Icons from '@element-plus/icons-vue';
import { PRIMARY_NAV_ITEMS } from '@/constants/navigation';
import { useXpbdSelector } from '@/composables/useXpbdSelector';

const props = defineProps<{
  roles: string[];
  showKnowledgeSpaceAction?: boolean;
}>();

const sidebarElement = ref<HTMLElement | null>(null);
const route = useRoute();
const primaryNavIndex = computed(() => (
  PRIMARY_NAV_ITEMS.findIndex((item) => route.path.startsWith(item.to))
));
const { position: primaryIndicatorPosition } = useXpbdSelector(primaryNavIndex);
const primaryIndicatorStyle = computed(() => ({
  '--sidebar-indicator-position': String(primaryIndicatorPosition.value),
  '--sidebar-indicator-opacity': primaryNavIndex.value >= 0 ? '1' : '0',
}));
let scrollbarTimer: number | undefined;

const emit = defineEmits<{
  openKnowledgeSpace: [];
}>();

const configNavItems = computed(() => {
  const items = [
    { to: '/config/prompt', label: '提示词配置', icon: 'EditPen' },
  ];

  if (props.roles.includes('ADMIN')) {
    items.push({ to: '/knowledge/admin/traces', label: '智能体 Trace', icon: 'Monitor' });
    items.push({ to: '/knowledge/admin/agent-governance', label: '智能体治理', icon: 'SetUp' });
    items.push({ to: '/knowledge/admin/skills', label: '技能管理', icon: 'Operation' });
    items.push({ to: '/knowledge/admin/memories', label: '记忆审核', icon: 'Collection' });
    items.push({ to: '/config/system', label: '系统配置', icon: 'Setting' });
  }

  return items;
});

function getIcon(name: string) {
  return (Icons as Record<string, unknown>)[name];
}

function revealScrollbar() {
  const element = sidebarElement.value;
  if (!element) {
    return;
  }
  element.classList.add('is-scrolling');
  if (scrollbarTimer !== undefined && typeof window !== 'undefined') {
    window.clearTimeout(scrollbarTimer);
  }
  if (typeof window !== 'undefined') {
    scrollbarTimer = window.setTimeout(() => {
      element.classList.remove('is-scrolling');
      scrollbarTimer = undefined;
    }, 850);
  }
}

onBeforeUnmount(() => {
  if (scrollbarTimer !== undefined && typeof window !== 'undefined') {
    window.clearTimeout(scrollbarTimer);
  }
});
</script>

<template>
  <aside ref="sidebarElement" class="app-sidebar" @scroll.passive="revealScrollbar">
    <div class="app-sidebar__brand">
      <div class="app-sidebar__logo">
        <svg width="28" height="28" viewBox="0 0 28 28" fill="none" xmlns="http://www.w3.org/2000/svg">
          <rect x="3" y="4" width="16" height="20" rx="2" fill="var(--color-primary-soft)" stroke="var(--color-accent)" stroke-width="1.5"/>
          <rect x="7" y="4" width="16" height="20" rx="2" fill="color-mix(in srgb, var(--color-secondary) 10%, transparent)" stroke="var(--color-primary)" stroke-width="1.5"/>
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

    <nav class="app-sidebar__nav app-sidebar__nav--primary" aria-label="主导航" :style="primaryIndicatorStyle">
      <span class="app-sidebar__indicator" aria-hidden="true"></span>
      <RouterLink
        v-for="item in PRIMARY_NAV_ITEMS"
        :key="item.to"
        class="app-sidebar__link"
        :to="item.to"
        :class="{ 'app-sidebar__link--primary': item.primary }"
        active-class="is-active"
      >
        <el-icon :size="18" class="app-sidebar__link-icon"><component :is="getIcon(item.icon)" /></el-icon>
        <span>{{ item.label }}</span>
      </RouterLink>
    </nav>

    <el-button
      v-if="showKnowledgeSpaceAction"
      class="app-sidebar__project-switch"
      data-test="knowledge-project-space-toggle"
      plain
      @click="emit('openKnowledgeSpace')"
    >
      <el-icon :size="17"><component :is="getIcon('FolderOpened')" /></el-icon>
      <span>问答项目空间</span>
    </el-button>

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
  position: fixed;
  z-index: 20;
  top: 1.35rem;
  left: 1.35rem;
  width: 330px;
  max-height: calc(100dvh - 2.7rem);
  overflow-y: auto;
  overflow-x: hidden;
  display: flex;
  flex-direction: column;
  gap: 1.65rem;
  padding: 1.6rem;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-xl);
  box-shadow: var(--shadow-soft);
  background:
    linear-gradient(160deg, color-mix(in srgb, var(--color-primary-soft) 52%, transparent), transparent 44%),
    color-mix(in srgb, var(--color-surface-strong) 98%, transparent);
  backdrop-filter: none;
  -webkit-backdrop-filter: none;
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
  position: relative;
}

.app-sidebar__indicator {
  position: absolute;
  z-index: 0;
  top: 0;
  left: 0;
  right: 0;
  height: 44px;
  border: 1px solid color-mix(in srgb, var(--color-primary) 28%, var(--color-border));
  border-radius: 0.85rem;
  background:
    linear-gradient(145deg, color-mix(in srgb, white 28%, transparent), transparent 62%),
    color-mix(in srgb, var(--color-primary-soft) 76%, var(--color-surface));
  box-shadow: var(--shadow-card);
  opacity: var(--sidebar-indicator-opacity);
  pointer-events: none;
  transform: translate3d(0, calc(var(--sidebar-indicator-position) * (44px + 0.3rem)), 0);
  transition: opacity var(--motion-fast) ease;
  will-change: transform;
}

.app-sidebar__project-switch {
  justify-content: flex-start;
  min-height: 42px;
  border-radius: 8px;
  font-weight: 650;
  transition: transform var(--motion-base) var(--motion-spring),
    color var(--motion-fast) ease, border-color var(--motion-fast) ease,
    background var(--motion-fast) ease;
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
  transition: transform var(--motion-base) var(--motion-spring),
    color var(--motion-fast) ease, background var(--motion-fast) ease,
    border-color var(--motion-fast) ease, box-shadow var(--motion-fast) ease;
  position: relative;
  z-index: 1;
}

.app-sidebar__link-icon {
  flex-shrink: 0;
  transition: color var(--motion-fast) ease, transform var(--motion-base) var(--motion-spring);
}

.app-sidebar__link--secondary {
  font-size: 0.875rem;
  min-height: 40px;
}

.app-sidebar__link--primary {
  color: var(--color-primary);
  border-color: transparent;
  background: transparent;
}

.app-sidebar__link--primary .app-sidebar__link-icon {
  width: 28px;
  height: 28px;
  margin-left: -0.2rem;
  border-radius: 0.55rem;
  color: #fff;
  background: var(--gradient-primary);
  box-shadow: 0 5px 12px color-mix(in srgb, var(--color-primary) 22%, transparent);
}

.app-sidebar__link:hover {
  color: var(--color-text);
  border-color: color-mix(in srgb, var(--color-accent) 28%, var(--color-border));
  background: color-mix(in srgb, var(--color-primary-soft) 42%, var(--color-surface));
  transform: translateX(3px);
}

.app-sidebar__link.is-active {
  color: var(--color-primary);
  border-color: color-mix(in srgb, var(--color-accent-strong) 28%, var(--color-border));
  background: var(--gradient-soft);
  box-shadow: var(--shadow-card);
}

.app-sidebar__nav--primary .app-sidebar__link.is-active {
  border-color: transparent;
  background: transparent;
  box-shadow: none;
}

.app-sidebar__nav--primary .app-sidebar__link.is-active::before {
  display: none;
}

.app-sidebar__link.is-active .app-sidebar__link-icon {
  transform: scale(1.08);
}

.app-sidebar__link:active,
.app-sidebar__project-switch:active {
  transform: translateX(1px) scale(var(--motion-press-scale));
}

.app-sidebar__link.is-active::before {
  content: '';
  position: absolute;
  left: 0;
  top: 25%;
  bottom: 25%;
  width: 3px;
  border-radius: 0 3px 3px 0;
  background: var(--gradient-primary);
  transform: scaleY(0.82);
  transform-origin: center;
  transition: transform var(--motion-base) var(--motion-spring);
}

@media (max-width: 980px) and (min-width: 769px) {
  .app-sidebar {
    top: 1rem;
    left: 1rem;
    width: 280px;
    max-height: calc(100dvh - 2rem);
  }
}

@media (prefers-reduced-motion: reduce) {
  .app-sidebar__link,
  .app-sidebar__link-icon,
  .app-sidebar__project-switch,
  .app-sidebar__link.is-active::before,
  .app-sidebar__indicator {
    transition: none;
  }

  .app-sidebar__link:hover,
  .app-sidebar__link:active,
  .app-sidebar__project-switch:active {
    transform: none;
  }
}
</style>
