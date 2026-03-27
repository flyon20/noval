<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import { useRoute } from 'vue-router';
import { Moon, Sunny, SwitchButton } from '@element-plus/icons-vue';
import { getCurrentTheme, THEME_EVENT_NAME, toggleTheme, type AppTheme } from '@/lib/theme';

defineProps<{
  username: string;
  roles: string[];
}>();

const emit = defineEmits<{
  logout: [];
}>();

const route = useRoute();
const currentTheme = ref<AppTheme>('light');

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
  if (route.path.startsWith('/config/prompt')) {
    return { title: '提示词配置' };
  }
  if (route.path.startsWith('/config/system')) {
    return { title: '系统配置' };
  }
  return { title: '控制台' };
});

const userInitial = computed(() => {
  // props.username not directly accessible in computed without defineProps ref
  return '';
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
      <!-- Desktop: show username + roles + logout button -->
      <template class="app-header__desktop-only">
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
        <el-button plain type="primary" @click="emit('logout')">退出登录</el-button>
      </template>
      <!-- Mobile: compact avatar + icon button -->
      <div class="app-header__mobile-actions">
        <div class="app-header__avatar">{{ username ? username.charAt(0).toUpperCase() : 'U' }}</div>
        <el-button
          class="app-header__mobile-theme"
          circle
          plain
          :icon="currentTheme === 'dark' ? Sunny : Moon"
          size="small"
          @click="handleThemeToggle"
        />
        <el-button
          class="app-header__mobile-logout"
          circle
          plain
          :icon="SwitchButton"
          size="small"
          @click="emit('logout')"
        />
      </div>
    </div>
  </header>
</template>

<style scoped lang="scss">
.app-header {
  --app-header-mobile-height: 56px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 1rem;
  width: 100%;
  max-width: 100%;
  padding: 1.2rem 1.6rem;
  border-bottom: 1px solid var(--color-border);
  background: rgba(255, 255, 255, 0.72);
  backdrop-filter: blur(18px) saturate(1.2);
  -webkit-backdrop-filter: blur(18px) saturate(1.2);
  position: sticky;
  top: 0;
  z-index: 30;
}

.app-header__identity {
  display: grid;
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
}

.app-header__title {
  font-size: 1.25rem;
  font-family: var(--font-heading);
}

.app-header__user {
  color: var(--color-text-muted);
  font-size: 0.92rem;
}

.app-header__tag {
  background: rgba(92, 124, 250, 0.08);
  border-color: rgba(92, 124, 250, 0.18);
  color: var(--color-accent-strong);
}

.app-header__theme-toggle,
.app-header__mobile-theme,
.app-header__mobile-logout {
  border-color: color-mix(in srgb, var(--color-border-strong) 76%, transparent);
  background: color-mix(in srgb, var(--color-glass) 76%, transparent);
  backdrop-filter: blur(14px);
  -webkit-backdrop-filter: blur(14px);
}

/* Mobile: hide desktop elements, show compact ones */
.app-header__desktop-only {
  display: contents;
}

.app-header__mobile-actions {
  display: none;
  align-items: center;
  gap: 0.5rem;
}

.app-header__avatar {
  width: 32px;
  height: 32px;
  border-radius: 50%;
  background: linear-gradient(135deg, var(--color-accent), var(--color-primary));
  color: #fff;
  font-size: 0.8rem;
  font-weight: 700;
  display: flex;
  align-items: center;
  justify-content: center;
}

@media (max-width: 768px) {
  .app-header {
    padding: 0.75rem 0.875rem;
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
    background: color-mix(in srgb, var(--color-glass) 88%, transparent);
    box-shadow: 0 12px 32px rgba(15, 23, 42, 0.12);
  }

  .app-header__title {
    font-size: 1.05rem;
    max-width: 58vw;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  /* Hide desktop actions, show mobile compact actions */
  .app-header__actions > :not(.app-header__mobile-actions) {
    display: none;
  }

  .app-header__mobile-actions {
    display: flex;
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
