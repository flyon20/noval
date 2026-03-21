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
    size="420px"
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
        <p class="rank-drawer__body">{{ detail.intro }}</p>
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
.rank-drawer__trace {
  margin: 0;
}

.rank-drawer__heading p {
  color: var(--color-text-muted);
  font-size: 0.8rem;
}

.rank-drawer__heading h3 {
  font-size: 1.35rem;
}

.rank-drawer__meta {
  display: flex;
  justify-content: space-between;
  gap: 1rem;
  color: var(--color-text-muted);
  font-size: 0.9rem;
}

.rank-drawer__body {
  line-height: 1.8;
  white-space: pre-wrap;
}

.rank-drawer__trace {
  color: var(--color-text-muted);
  font-size: 0.85rem;
}
</style>
