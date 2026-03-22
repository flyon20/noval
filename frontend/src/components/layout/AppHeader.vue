<script setup lang="ts">
import { computed } from 'vue';
import { useRoute } from 'vue-router';

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
    return { title: '扫榜页', subtitle: '查看当前榜单与书籍详情。' };
  }
  if (route.path.startsWith('/analysis')) {
    return { title: '分析页', subtitle: '查看当前书籍分析结果。' };
  }
  if (route.path.startsWith('/trend')) {
    return { title: '趋势页', subtitle: '查看趋势结果与图表。' };
  }
  if (route.path.startsWith('/history')) {
    return { title: '历史页', subtitle: '回看历史分析记录。' };
  }
  if (route.path.startsWith('/config/prompt')) {
    return { title: '提示词配置', subtitle: '管理提示词内容。' };
  }
  if (route.path.startsWith('/config/system')) {
    return { title: '系统配置', subtitle: '管理系统参数。' };
  }
  return { title: '控制台', subtitle: '查看当前页面内容。' };
});
</script>

<template>
  <header class="app-header">
    <div class="app-header__identity">
      <p class="app-header__eyebrow">Current Page</p>
      <h2 class="app-header__title">{{ pageCopy.title }}</h2>
      <p class="app-header__subtitle">{{ pageCopy.subtitle }}</p>
    </div>

    <div class="app-header__actions">
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
    </div>
  </header>
</template>

<style scoped lang="scss">
.app-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 1rem;
  padding: 1.4rem 1.6rem;
  border-bottom: 1px solid var(--color-border);
  background:
    linear-gradient(90deg, rgba(255, 255, 255, 0.56), rgba(255, 255, 255, 0.1));
}

.app-header__identity {
  display: grid;
  gap: 0.25rem;
}

.app-header__actions {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  flex-wrap: wrap;
  justify-content: flex-end;
}

.app-header__eyebrow,
.app-header__title,
.app-header__subtitle {
  margin: 0;
}

.app-header__eyebrow {
  color: var(--color-accent-strong);
  font-size: 0.74rem;
  letter-spacing: 0.16em;
  text-transform: uppercase;
}

.app-header__title {
  font-size: 1.35rem;
}

.app-header__subtitle,
.app-header__user {
  color: var(--color-text-muted);
}

.app-header__subtitle {
  line-height: 1.65;
}

.app-header__user {
  font-size: 0.92rem;
}

.app-header__tag {
  background: rgba(199, 146, 92, 0.08);
  border-color: rgba(199, 146, 92, 0.22);
  color: var(--color-accent-strong);
}

@media (max-width: 860px) {
  .app-header {
    display: grid;
  }

  .app-header__actions {
    justify-content: flex-start;
  }
}
</style>
