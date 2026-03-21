<script setup lang="ts">
defineProps<{
  username: string;
  roles: string[];
}>();

const emit = defineEmits<{
  logout: [];
}>();
</script>

<template>
  <header class="app-header">
    <div>
      <p class="app-header__eyebrow">当前会话</p>
      <h2 class="app-header__title">{{ username }}</h2>
    </div>

    <div class="app-header__actions">
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
  padding: 1.25rem 1.5rem;
  border-bottom: 1px solid var(--color-border);
}

.app-header__eyebrow {
  margin: 0 0 0.35rem;
  color: var(--color-text-muted);
  font-size: 0.75rem;
  letter-spacing: 0.12em;
  text-transform: uppercase;
}

.app-header__title {
  margin: 0;
  font-size: 1.25rem;
}

.app-header__actions {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  flex-wrap: wrap;
  justify-content: flex-end;
}

.app-header__tag {
  background: rgba(210, 136, 61, 0.08);
  border-color: rgba(210, 136, 61, 0.18);
  color: var(--color-accent-strong);
}
</style>
