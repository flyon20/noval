<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue';
import { useRoute } from 'vue-router';
import AppHeader from '@/components/layout/AppHeader.vue';
import AppSidebar from '@/components/layout/AppSidebar.vue';
import AppBottomNav from '@/components/layout/AppBottomNav.vue';
import KnowledgeProjectSpace from '@/components/knowledge/KnowledgeProjectSpace.vue';

defineProps<{
  username: string;
  roles: string[];
}>();

const emit = defineEmits<{
  changePassword: [];
  logout: [];
}>();

const route = useRoute();
const knowledgeSidebarMode = ref<'projects' | 'nav'>('projects');
const mobileProjectDrawerVisible = ref(false);
const isKnowledgeChatRoute = computed(() => route.path === '/knowledge');

function syncKnowledgeRouteScrollLock(locked: boolean) {
  if (typeof document === 'undefined') {
    return;
  }

  document.documentElement.classList.toggle('knowledge-chat-route', locked);
  document.body.classList.toggle('knowledge-chat-route', locked);
}

watch(
  () => route.path,
  (path) => {
    knowledgeSidebarMode.value = path === '/knowledge' ? 'projects' : 'nav';
    if (path !== '/knowledge') {
      mobileProjectDrawerVisible.value = false;
    }
    syncKnowledgeRouteScrollLock(path === '/knowledge');
  },
  { immediate: true },
);

onBeforeUnmount(() => {
  syncKnowledgeRouteScrollLock(false);
});
</script>

<template>
  <div class="app-shell" :class="{ 'is-knowledge-chat': isKnowledgeChatRoute }">
    <div class="app-shell__backdrop"></div>

    <div class="app-shell__sidebar" data-test="knowledge-sidebar-mode">
      <KnowledgeProjectSpace
        v-if="isKnowledgeChatRoute && knowledgeSidebarMode === 'projects'"
        :showMainNavAction="true"
        @show-main-nav="knowledgeSidebarMode = 'nav'"
      />
      <AppSidebar
        v-else
        :roles="roles"
        :show-knowledge-space-action="isKnowledgeChatRoute"
        @open-knowledge-space="knowledgeSidebarMode = 'projects'"
      />
    </div>

    <div class="app-shell__surface">
      <AppHeader
        :roles="roles"
        :username="username"
        @change-password="emit('changePassword')"
        @open-knowledge-projects="mobileProjectDrawerVisible = true"
        @logout="emit('logout')"
      />
      <main class="app-shell__content" :class="{ 'is-knowledge-chat': isKnowledgeChatRoute }">
        <slot />
      </main>
      <AppBottomNav class="app-shell__mobile-nav" />
    </div>

    <el-drawer
      v-if="isKnowledgeChatRoute"
      v-model="mobileProjectDrawerVisible"
      direction="ltr"
      size="50%"
      :with-header="false"
      append-to-body
      class="app-shell__knowledge-project-drawer"
      data-test="knowledge-mobile-project-drawer"
    >
      <KnowledgeProjectSpace
        embedded
        close-on-select
        @close="mobileProjectDrawerVisible = false"
      />
    </el-drawer>
  </div>
</template>

<style scoped lang="scss">
.app-shell {
  position: relative;
  display: grid;
  grid-template-columns: minmax(290px, 330px) 1fr;
  gap: 1.5rem;
  min-height: 100vh;
  max-width: 100%;
  padding: 1.35rem;
  background: linear-gradient(135deg, var(--color-bg), var(--color-bg-secondary));
}

.app-shell__backdrop {
  position: absolute;
  inset: 0;
  pointer-events: none;
  background:
    linear-gradient(130deg, color-mix(in srgb, var(--color-primary-soft) 34%, transparent), transparent 46%),
    linear-gradient(315deg, color-mix(in srgb, var(--color-accent) 8%, transparent), transparent 52%),
    repeating-linear-gradient(90deg, color-mix(in srgb, var(--color-primary) 4%, transparent) 0 1px, transparent 1px var(--workspace-grid-size)),
    repeating-linear-gradient(0deg, color-mix(in srgb, var(--color-secondary) 4%, transparent) 0 1px, transparent 1px var(--workspace-grid-size));
  --workspace-grid-size: 64px;
}

.app-shell__surface {
  position: relative;
  isolation: isolate;
  min-width: 0;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-xl);
  background: var(--color-surface);
  box-shadow: var(--shadow-soft);
}

.app-shell__surface::before {
  content: '';
  position: absolute;
  z-index: 1;
  top: 0;
  left: var(--radius-xl);
  right: var(--radius-xl);
  height: 1px;
  pointer-events: none;
  background: linear-gradient(90deg, transparent, color-mix(in srgb, var(--color-accent) 52%, transparent), transparent);
  opacity: 0.72;
}

.app-shell__sidebar {
  position: relative;
  min-width: 0;
  align-self: start;
}

.app-shell__surface {
  display: flex;
  flex-direction: column;
  overflow: visible;
  max-width: 100%;
  backdrop-filter: none;
  -webkit-backdrop-filter: none;
}

.app-shell__content {
  padding: 1.5rem;
  flex: 1;
  min-width: 0;
}

.app-shell__surface :deep(.rank-page__hero),
.app-shell__surface :deep(.rank-page__panel),
.app-shell__surface :deep(.rank-page__hero-badge),
.app-shell__surface :deep(.rank-page__page-size),
.app-shell__surface :deep(.rank-page__snapshot-card),
.app-shell__surface :deep(.rank-page__mobile-update),
.app-shell__surface :deep(.rank-page__item),
.app-shell__surface :deep(.trend-context),
.app-shell__surface :deep(.trend-page__toolbar),
.app-shell__surface :deep(.trend-page__support-card),
.app-shell__surface :deep(.trend-page__visual-header),
.app-shell__surface :deep(.trend-chart-card),
.app-shell__surface :deep(.trend-summary__card),
.app-shell__surface :deep(.trend-comparison-list),
.app-shell__surface :deep(.trend-result-preview__card),
.app-shell__surface :deep(.analysis-context),
.app-shell__surface :deep(.analysis-result-card) {
  backdrop-filter: none;
  -webkit-backdrop-filter: none;
  background:
    linear-gradient(
      180deg,
      color-mix(in srgb, var(--color-surface-strong) 98%, transparent),
      color-mix(in srgb, var(--color-surface) 94%, transparent)
    );
  box-shadow: 0 12px 28px color-mix(in srgb, var(--color-primary) 8%, transparent);
}

.app-shell__surface :deep(.rank-page__item),
.app-shell__surface :deep(.trend-result-preview__card),
.app-shell__surface :deep(.analysis-result-card) {
  transition: transform var(--motion-base) var(--motion-spring),
    border-color var(--motion-fast) ease, box-shadow var(--motion-fast) ease;
}

.app-shell__surface :deep(.rank-page__item:hover),
.app-shell__surface :deep(.analysis-result-card:hover) {
  box-shadow: 0 14px 30px color-mix(in srgb, var(--color-primary) 12%, transparent);
}

@media (hover: hover) and (pointer: fine) {
  .app-shell__surface :deep(.rank-page__item:hover),
  .app-shell__surface :deep(.trend-result-preview__card:hover),
  .app-shell__surface :deep(.analysis-result-card:hover) {
    transform: translateY(-2px);
    border-color: color-mix(in srgb, var(--color-primary) 24%, var(--color-border));
  }
}

.app-shell__surface :deep(.rank-page__item:active) {
  transform: translateY(0) scale(0.995);
}

/* Tablet breakpoint */
@media (max-width: 980px) and (min-width: 769px) {
  .app-shell {
    grid-template-columns: minmax(240px, 280px) 1fr;
    gap: 1rem;
    padding: 1rem;
  }

}

/* Mobile breakpoint */
@media (max-width: 768px) {
  .app-shell {
    grid-template-columns: 1fr;
    gap: 0;
    padding: 0;
    min-height: 100dvh;
    background: var(--color-bg);
    overflow-x: clip;
  }

  .app-shell.is-knowledge-chat {
    height: 100dvh;
    min-height: 100dvh;
    overflow: hidden;
    overscroll-behavior: none;
  }

  .app-shell__backdrop {
    display: none;
  }

  .app-shell__sidebar {
    display: none;
  }

  .app-shell__surface {
    border: none;
    border-radius: 0;
    box-shadow: none;
    background: transparent;
    backdrop-filter: none;
    min-height: 100dvh;
    overflow: visible;
  }

  .app-shell__surface::before {
    display: none;
  }

  .app-shell.is-knowledge-chat .app-shell__surface {
    height: 100%;
    min-height: 0;
    overflow: hidden;
  }

  .app-shell__content {
    padding:
      calc(0.5rem + 56px)
      0.875rem
      calc(var(--bottom-nav-height) + env(safe-area-inset-bottom, 0px) + 1.5rem);
    width: 100%;
    max-width: 100%;
    overflow-x: clip;
  }

  .app-shell__content.is-knowledge-chat {
    display: flex;
    flex: 1 1 auto;
    flex-direction: column;
    height: 100%;
    min-height: 0;
    padding: calc(0.5rem + 56px) 0 0;
    overflow: hidden;
    overscroll-behavior: none;
  }
}

:global(.app-shell__knowledge-project-drawer .el-drawer__body) {
  padding: 0;
  overflow: hidden;
}

:global(.app-shell__knowledge-project-drawer) {
  max-width: 100vw;
}
</style>
