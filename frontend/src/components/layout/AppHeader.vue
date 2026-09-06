<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import { useRoute } from 'vue-router';
import { FolderOpened, Moon, Sunny, SwitchButton, UserFilled } from '@element-plus/icons-vue';
import { getCurrentTheme, THEME_EVENT_NAME, toggleTheme, type AppTheme } from '@/lib/theme';

defineProps<{
  username: string;
  roles: string[];
}>();

const emit = defineEmits<{
  changePassword: [];
  logout: [];
  openKnowledgeProjects: [];
}>();

const route = useRoute();
const currentTheme = ref<AppTheme>('light');
const isKnowledgeChatRoute = computed(() => route.path === '/knowledge');

const pageCopy = computed(() => {
  if (route.path.startsWith('/rank')) {
    return { title: '扫榜' };
  }
  if (route.path.startsWith('/analysis')) {
    return { title: '单书分析' };
  }
  if (route.path.startsWith('/trend')) {
    return { title: '趋势分析' };
  }
  if (route.path.startsWith('/history')) {
    return { title: '历史回看' };
  }
  if (route.path.startsWith('/knowledge')) {
    return { title: 'AI 问答' };
  }
  if (route.path.startsWith('/config/prompt')) {
    return { title: '提示词配置' };
  }
  if (route.path.startsWith('/config/system')) {
    return { title: '系统配置' };
  }
  return { title: '控制台' };
});

function syncTheme(theme?: AppTheme) {
  currentTheme.value = theme ?? getCurrentTheme();
}

function handleThemeToggle() {
  syncTheme(toggleTheme());
}

function handleThemeChange(event: Event) {
  syncTheme((event as CustomEvent<AppTheme>).detail);
}

onMounted(() => {
  syncTheme();
  document.addEventListener(THEME_EVENT_NAME, handleThemeChange as EventListener);
});

onBeforeUnmount(() => {
  document.removeEventListener(THEME_EVENT_NAME, handleThemeChange as EventListener);
});
</script>

<template>
  <header class="app-header">
    <div class="app-header__identity">
      <h2 class="app-header__title">{{ pageCopy.title }}</h2>
    </div>

    <div class="app-header__actions">
      <div class="app-header__desktop-only">
        <span class="app-header__user">{{ username }}</span>
        <el-tag
          v-for="role in roles"
          :key="role"
          class="app-header__tag"
          effect="plain"
          round
        >
          {{ role }}
        </el-tag>
        <el-button
          class="app-header__theme-toggle"
          circle
          plain
          :icon="currentTheme === 'dark' ? Sunny : Moon"
          @click="handleThemeToggle"
        />
        <el-button plain @click="emit('changePassword')">修改密码</el-button>
        <el-button plain type="primary" @click="emit('logout')">退出登录</el-button>
      </div>

      <div class="app-header__mobile-actions">
        <el-button
          v-if="isKnowledgeChatRoute"
          class="app-header__mobile-project"
          circle
          plain
          :icon="FolderOpened"
          size="small"
          aria-label="项目空间"
          data-test="knowledge-mobile-project-open"
          @click="emit('openKnowledgeProjects')"
        />
        <el-dropdown trigger="click">
          <button class="app-header__avatar-button" type="button" aria-label="账户菜单">
            <span class="app-header__avatar">{{ username ? username.charAt(0).toUpperCase() : 'U' }}</span>
          </button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item :icon="UserFilled" @click="emit('changePassword')">修改密码</el-dropdown-item>
              <el-dropdown-item :icon="SwitchButton" @click="emit('logout')">退出登录</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
        <el-button
          class="app-header__mobile-theme"
          circle
          plain
          :icon="currentTheme === 'dark' ? Sunny : Moon"
          size="small"
          aria-label="切换主题"
          @click="handleThemeToggle"
        />
      </div>
    </div>
  </header>
</template>

<style scoped lang="scss">
.app-header {
  --app-header-mobile-height: 56px;
  display: flex;
  flex: 0 0 auto;
  align-items: center;
  justify-content: space-between;
  gap: 1rem;
  width: 100%;
  max-width: 100%;
  padding: 1.2rem 1.6rem;
  border-bottom: 1px solid var(--color-border);
  background:
    linear-gradient(90deg, color-mix(in srgb, var(--color-primary-soft) 34%, transparent), transparent 42%),
    color-mix(in srgb, var(--color-surface-strong) 96%, transparent);
  backdrop-filter: blur(10px) saturate(1.08);
  -webkit-backdrop-filter: blur(10px) saturate(1.08);
  position: sticky;
  top: 0;
  z-index: 30;
  box-shadow: 0 10px 24px color-mix(in srgb, var(--color-primary) 8%, transparent);
}

.app-header__identity {
  display: grid;
  min-width: 0;
  gap: 0.2rem;
}

.app-header__actions {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  flex-wrap: wrap;
  justify-content: flex-end;
  min-width: 0;
}

.app-header__title {
  margin: 0;
  font-size: 1.25rem;
  font-family: var(--font-heading);
}

.app-header__user {
  max-width: 12rem;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--color-text-muted);
  font-size: 0.92rem;
}

.app-header__tag {
  background: color-mix(in srgb, var(--color-primary-soft) 72%, transparent);
  border-color: color-mix(in srgb, var(--color-primary) 24%, var(--color-border));
  color: var(--color-accent-strong);
}

.app-header__theme-toggle,
.app-header__mobile-project,
.app-header__mobile-theme {
  display: inline-flex;
  flex: 0 0 auto;
  align-items: center;
  justify-content: center;
  aspect-ratio: 1;
  padding: 0;
  border-color: color-mix(in srgb, var(--color-border-strong) 76%, transparent);
  background: color-mix(in srgb, var(--color-primary-soft) 42%, var(--color-surface-strong));
  transition: transform var(--motion-base) var(--motion-spring),
    color var(--motion-fast) ease, background var(--motion-fast) ease,
    box-shadow var(--motion-fast) ease;
}

.app-header__mobile-project {
  width: 44px;
  min-width: 44px;
  height: 44px;
  min-height: 44px;
}

.app-header__desktop-only {
  display: contents;
}

.app-header__actions :deep(.el-button + .el-button) {
  margin-left: 0;
}

.app-header__mobile-actions {
  display: none;
  align-items: center;
  gap: 0.5rem;
}

.app-header__avatar-button {
  display: inline-flex;
  flex: 0 0 44px;
  align-items: center;
  justify-content: center;
  width: 44px;
  min-width: 44px;
  height: 44px;
  min-height: 44px;
  padding: 0;
  border: 0;
  background: transparent;
  cursor: pointer;
  transition: transform var(--motion-base) var(--motion-spring);
}

.app-header__avatar {
  width: 36px;
  height: 36px;
  flex: 0 0 36px;
  aspect-ratio: 1;
  border-radius: 50%;
  background: var(--gradient-primary);
  color: #fff;
  font-size: 0.8rem;
  font-weight: 700;
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: 0 7px 16px color-mix(in srgb, var(--color-primary) 22%, transparent);
  transition: transform var(--motion-base) var(--motion-spring),
    box-shadow var(--motion-fast) ease;
}

@media (hover: hover) and (pointer: fine) {
  .app-header__avatar-button:hover .app-header__avatar,
  .app-header__theme-toggle:hover,
  .app-header__mobile-project:hover,
  .app-header__mobile-theme:hover {
    transform: translateY(-2px);
    box-shadow: 0 10px 20px color-mix(in srgb, var(--color-primary) 26%, transparent);
  }
}

.app-header__avatar-button:active,
.app-header__theme-toggle:active,
.app-header__mobile-project:active,
.app-header__mobile-theme:active {
  transform: translateY(1px) scale(var(--motion-press-scale));
}

@media (max-width: 768px) {
  .app-header {
    padding: 0.375rem 0.75rem;
    min-height: var(--app-header-mobile-height);
    position: fixed;
    top: 0;
    left: 0;
    right: 0;
    width: auto;
    max-width: none;
    z-index: 40;
    border-radius: 0;
    border-bottom-color: rgba(255, 255, 255, 0.16);
    background: color-mix(in srgb, var(--color-surface-strong) 94%, transparent);
    backdrop-filter: blur(8px) saturate(1.04);
    -webkit-backdrop-filter: blur(8px) saturate(1.04);
    box-shadow: 0 8px 18px color-mix(in srgb, var(--color-primary) 10%, transparent);
  }

  .app-header__title {
    font-size: 1.05rem;
    max-width: min(58vw, 14rem);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .app-header__actions > :not(.app-header__mobile-actions) {
    display: none;
  }

  .app-header__mobile-actions {
    display: flex;
    flex: 0 0 auto;
    gap: 0.25rem;
  }

  .app-header__mobile-theme {
    width: 44px;
    min-width: 44px;
    height: 44px;
    min-height: 44px;
  }
}

@media (max-width: 860px) and (min-width: 769px) {
  .app-header {
    display: grid;
  }

  .app-header__actions {
    justify-content: flex-start;
  }
}
</style>
