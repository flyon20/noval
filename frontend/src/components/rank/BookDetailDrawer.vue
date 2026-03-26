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

function closeDrawer() {
  visible.value = false;
}

const uiText = {
  heading: '简介',
  loadingTitle: '加载中...',
  authorLabel: '作者',
  sourceLinkLabel: '链接',
  introLabel: '简介',
  closeLabel: '关闭',
};
</script>

<template>
  <el-drawer
    v-model="visible"
    :append-to-body="true"
    :destroy-on-close="false"
    :with-header="false"
    size="440px"
  >
    <div class="rank-drawer" data-testid="rank-detail-surface">
      <div class="rank-drawer__topbar">
        <div class="rank-drawer__heading" data-testid="rank-detail-heading">
          <h3 data-testid="rank-detail-title">{{ detail?.bookName ?? uiText.loadingTitle }}</h3>
        </div>
        <el-button
          class="rank-drawer__close"
          data-testid="rank-detail-close"
          plain
          type="default"
          @click="closeDrawer"
        >
          {{ uiText.closeLabel }}
        </el-button>
      </div>

      <el-skeleton v-if="loading" animated :rows="6" />

      <template v-else-if="detail">
        <div class="rank-drawer__meta" data-testid="rank-detail-meta">
          <span>{{ uiText.authorLabel }}{{ detail.author }}</span>
          <a :href="detail.bookUrl" rel="noreferrer" target="_blank">{{ uiText.sourceLinkLabel }}</a>
        </div>
        <section class="rank-drawer__section">
          <p class="rank-drawer__body" data-testid="rank-detail-intro">{{ detail.intro }}</p>
        </section>
      </template>
    </div>
  </el-drawer>
</template>

<style scoped lang="scss">
.rank-drawer {
  display: grid;
  gap: 1rem;
  min-width: 0;
  color: var(--color-text);
}

.rank-drawer__topbar {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 1rem;
  padding-bottom: 0.35rem;
  border-bottom: 1px solid color-mix(in srgb, var(--color-border) 82%, transparent);
}

.rank-drawer__heading {
  display: grid;
  gap: 0.35rem;
  min-width: 0;
  flex: 1;
}

.rank-drawer__heading h3,
.rank-drawer__body {
  margin: 0;
}

.rank-drawer__heading h3 {
  color: var(--color-text);
  font-size: 1.4rem;
  line-height: 1.3;
  overflow-wrap: anywhere;
  word-break: break-word;
}

.rank-drawer__meta {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  flex-wrap: wrap;
  gap: 1rem;
  color: var(--color-text-muted);
  font-size: 0.92rem;
  padding: 0.25rem 0.1rem 0;
}

.rank-drawer__meta a,
.rank-drawer__meta span {
  overflow-wrap: anywhere;
  word-break: break-word;
}

.rank-drawer__close {
  flex-shrink: 0;
  min-height: 2.5rem;
  padding-inline: 0.95rem;
  border-color: color-mix(in srgb, var(--color-border-strong) 70%, transparent);
  background: color-mix(in srgb, var(--color-glass) 72%, transparent);
  backdrop-filter: blur(14px);
}

.rank-drawer__section {
  display: grid;
  gap: 0.6rem;
  padding: 1rem 1.1rem;
  border: 1px solid color-mix(in srgb, var(--color-border) 82%, transparent);
  border-radius: 1.1rem;
  background:
    linear-gradient(160deg, rgba(255, 255, 255, 0.24), rgba(255, 255, 255, 0.08)),
    color-mix(in srgb, var(--color-surface) 88%, transparent);
  backdrop-filter: blur(18px) saturate(1.12);
  -webkit-backdrop-filter: blur(18px) saturate(1.12);
  box-shadow: var(--shadow-card);
}

.rank-drawer__body {
  line-height: 1.85;
  white-space: pre-wrap;
}

@media (max-width: 920px) {
  .rank-drawer__topbar {
    gap: 0.75rem;
  }

  .rank-drawer__close {
    min-width: 4.5rem;
  }
}
</style>
