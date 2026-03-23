<script setup lang="ts">
import { computed } from 'vue';
import { useRoute } from 'vue-router';
import { SwitchButton } from '@element-plus/icons-vue';

defineProps<{
  username: string;
  roles: string[];
}>();

const emit = defineEmits<{
  logout: [];
}>();

const route = useRoute();

const pageCopy = computed(() => {
  if (route.path.startsWith('/rank')) {
    return { title: '扫榜', subtitle: '查看当前榜单与书籍详情。' };
  }
  if (route.path.startsWith('/analysis')) {
    return { title: '单书分析', subtitle: '查看当前书籍分析结果。' };
  }
  if (route.path.startsWith('/trend')) {
    return { title: '趋势分析', subtitle: '查看趋势结果与图表。' };
  }
  if (route.path.startsWith('/history')) {
    return { title: '历史回看', subtitle: '回看历史分析记录。' };
  }
  if (route.path.startsWith('/config/prompt')) {
    return { title: '提示词配置', subtitle: '管理提示词内容。' };
  }
  if (route.path.startsWith('/config/system')) {
    return { title: '系统配置', subtitle: '管理系统参数。' };
  }
  return { title: '控制台', subtitle: '查看当前页面内容。' };
});

const userInitial = computed(() => {
  // props.username not directly accessible in computed without defineProps ref
  return '';
});
</script>

<template>
  <header class="app-header">
    <div class="app-header__identity">
      <h2 class="app-header__title">{{ pageCopy.title }}</h2>
      <p class="app-header__subtitle">{{ pageCopy.subtitle }}</p>
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
        <el-button plain type="primary" @click="emit('logout')">退出登录</el-button>
      </template>
      <!-- Mobile: compact avatar + icon button -->
      <div class="app-header__mobile-actions">
        <div class="app-header__avatar">{{ username ? username.charAt(0).toUpperCase() : 'U' }}</div>
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
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 1rem;
  padding: 1.2rem 1.6rem;
  border-bottom: 1px solid var(--color-border);
  background:
    linear-gradient(90deg, rgba(255, 255, 255, 0.56), rgba(255, 255, 255, 0.1));
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
}

.app-header__title,
.app-header__subtitle {
  margin: 0;
}

.app-header__title {
  font-size: 1.25rem;
  font-family: var(--font-heading);
}

.app-header__subtitle {
  color: var(--color-text-muted);
  font-size: 0.85rem;
  line-height: 1.5;
}

.app-header__user {
  color: var(--color-text-muted);
  font-size: 0.92rem;
}

.app-header__tag {
  background: rgba(199, 146, 92, 0.08);
  border-color: rgba(199, 146, 92, 0.22);
  color: var(--color-accent-strong);
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

.app-header__mobile-logout {
  border-color: rgba(36, 61, 54, 0.2);
}

@media (max-width: 768px) {
  .app-header {
    padding: 0.75rem 0.875rem;
    min-height: 52px;
  }

  .app-header__subtitle {
    display: none;
  }

  .app-header__title {
    font-size: 1.05rem;
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
