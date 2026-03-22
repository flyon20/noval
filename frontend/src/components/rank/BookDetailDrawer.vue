<script setup lang="ts">
import { computed } from 'vue';
import type { BookDetail } from '@/types/crawler';

const props = defineProps<{
  modelValue: boolean;
  detail?: BookDetail | null;
  loading?: boolean;
  traceId?: string;
}>();

const emit = defineEmits<{
  'update:modelValue': [value: boolean];
}>();

const visible = computed({
  get: () => props.modelValue,
  set: (value: boolean) => emit('update:modelValue', value),
});
</script>

<template>
  <el-drawer
    v-model="visible"
    :append-to-body="false"
    :destroy-on-close="false"
    :with-header="false"
    size="440px"
  >
    <div class="rank-drawer">
      <div class="rank-drawer__heading">
        <p>书籍详情</p>
        <h3>{{ detail?.bookName ?? '加载中' }}</h3>
      </div>

      <el-skeleton v-if="loading" animated :rows="6" />

      <template v-else-if="detail">
        <div class="rank-drawer__meta">
          <span>作者：{{ detail.author }}</span>
          <a :href="detail.bookUrl" rel="noreferrer" target="_blank">原始链接</a>
        </div>
        <section class="rank-drawer__section">
          <p class="rank-drawer__label">完整简介</p>
          <p class="rank-drawer__body">{{ detail.intro }}</p>
        </section>
      </template>

      <p v-if="traceId" class="rank-drawer__trace">traceId: {{ traceId }}</p>
    </div>
  </el-drawer>
</template>

<style scoped lang="scss">
.rank-drawer {
  display: grid;
  gap: 1rem;
}

.rank-drawer__heading p,
.rank-drawer__heading h3,
.rank-drawer__body,
.rank-drawer__trace,
.rank-drawer__label {
  margin: 0;
}

.rank-drawer__heading p,
.rank-drawer__label,
.rank-drawer__trace {
  color: var(--color-text-muted);
  font-size: 0.84rem;
}

.rank-drawer__heading h3 {
  font-size: 1.4rem;
  line-height: 1.3;
}

.rank-drawer__meta {
  display: flex;
  justify-content: space-between;
  gap: 1rem;
  color: var(--color-text-muted);
  font-size: 0.92rem;
}

.rank-drawer__section {
  display: grid;
  gap: 0.6rem;
  padding: 1rem 1.1rem;
  border: 1px solid var(--color-border);
  border-radius: 1.1rem;
  background: rgba(255, 255, 255, 0.74);
}

.rank-drawer__body {
  line-height: 1.85;
  white-space: pre-wrap;
}
</style>
