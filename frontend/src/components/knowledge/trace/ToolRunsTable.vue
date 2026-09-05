<script setup lang="ts">
import { computed } from 'vue';
import { knowledgeStatusLabel } from '@/utils/knowledgeDisplay';

interface Props {
  toolRunsJson?: string;
}

const props = defineProps<Props>();

const toolRuns = computed<Record<string, unknown>[]>(() => {
  if (!props.toolRunsJson) return [];
  try {
    const parsed = JSON.parse(props.toolRunsJson);
    return Array.isArray(parsed)
      ? parsed.filter((item): item is Record<string, unknown> => Boolean(objectValue(item)))
      : [];
  } catch {
    return [];
  }
});

const getStatusType = (status: unknown) => {
  const normalized = String(status || '');
  if (normalized === 'succeeded') return 'success';
  if (normalized === 'failed') return 'danger';
  if (normalized === 'skipped') return 'info';
  return 'info';
};

function toolName(row: Record<string, unknown>, index: number) {
  return fieldText(row, 'name', `tool-${index + 1}`);
}

function fieldText(row: Record<string, unknown>, key: string, fallback = '') {
  const value = row[key];
  return value === undefined || value === null || value === '' ? fallback : String(value);
}

function resultCountText(row: Record<string, unknown>) {
  const value = row.resultCount;
  if (value === undefined || value === null || value === '') return '';
  return `${value} 条结果`;
}

function inputSummary(row: Record<string, unknown>) {
  const input = objectValue(row.input);
  if (!input) return [];
  return compactPairs([
    ['查询', shortText(input.query, 80)],
    ['来源类型', input.sourceType],
    ['书籍 ID', input.bookId],
    ['数量', input.limit],
    ['任务类型', input.taskType],
  ]);
}

function outputSummary(row: Record<string, unknown>) {
  const output = objectValue(row.output);
  if (!output) return [];
  return compactPairs([
    ['检索后端', output.retrievalBackend],
    ['快照时间', output.snapshotTime],
    ['结果项', arrayCount(output.items)],
    ['榜单条目', arrayCount(output.ranks)],
    ['书籍', arrayCount(output.books)],
    ['技能', arrayCount(output.skills)],
    ['已选技能', arrayCount(output.selectedSkills)],
    ['消息', shortText(output.message, 80)],
  ]);
}

function compactPairs(pairs: [string, unknown][]) {
  return pairs
    .filter(([, value]) => value !== undefined && value !== null && value !== '')
    .map(([key, value]) => `${key}: ${value}`);
}

function objectValue(value: unknown): Record<string, unknown> | null {
  return value && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : null;
}

function arrayCount(value: unknown) {
  return Array.isArray(value) ? value.length : undefined;
}

function shortText(value: unknown, maxLength: number) {
  const text = value == null ? '' : String(value);
  if (text.length <= maxLength) return text;
  return `${text.slice(0, maxLength).trim()}...`;
}
</script>

<template>
  <div class="tool-runs-table">
    <template v-if="!toolRuns.length">
      <p class="tool-runs-table__empty">暂无工具调用记录</p>
    </template>
    <template v-else>
      <div class="tool-runs-table__list" role="list">
        <article
          v-for="(row, index) in toolRuns"
          :key="`${toolName(row, index)}-${index}`"
          class="tool-run-card"
          data-test="tool-run-card"
          role="listitem"
        >
          <header class="tool-run-card__header">
            <div class="tool-run-card__identity">
              <strong>{{ toolName(row, index) }}</strong>
              <span v-if="fieldText(row, 'toolset')">{{ fieldText(row, 'toolset') }}</span>
            </div>
            <div class="tool-run-card__status">
              <el-tag :type="getStatusType(row.status)" size="small">
                {{ knowledgeStatusLabel(row.status, '未知') }}
              </el-tag>
              <span v-if="resultCountText(row)">{{ resultCountText(row) }}</span>
            </div>
          </header>

          <div class="tool-run-card__body">
            <section>
              <h4>输入</h4>
              <div class="tool-runs-table__summary">
                <el-tag v-for="item in inputSummary(row)" :key="item" size="small" type="info">
                  {{ item }}
                </el-tag>
                <span v-if="!inputSummary(row).length" class="tool-run-card__muted">无输入</span>
              </div>
            </section>
            <section>
              <h4>输出</h4>
              <div class="tool-runs-table__summary">
                <el-tag v-for="item in outputSummary(row)" :key="item" size="small" type="success">
                  {{ item }}
                </el-tag>
                <span v-if="!outputSummary(row).length" class="tool-run-card__muted">无输出</span>
              </div>
            </section>
            <section v-if="row.errorType || row.reason">
              <h4>错误</h4>
              <el-text type="danger" size="small">{{ row.errorType || row.reason }}</el-text>
            </section>
          </div>
        </article>
      </div>
    </template>
  </div>
</template>

<style scoped>
.tool-runs-table__empty {
  color: var(--el-text-color-secondary);
  font-style: italic;
  margin: 0;
}

.tool-runs-table__list {
  display: grid;
  gap: 0.625rem;
  min-width: 0;
}

.tool-run-card {
  min-width: 0;
  display: grid;
  gap: 0.625rem;
  padding: 0.75rem;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  background: var(--el-fill-color-extra-light);
}

.tool-run-card__header {
  min-width: 0;
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 0.75rem;
}

.tool-run-card__identity {
  min-width: 0;
  display: grid;
  gap: 0.125rem;
}

.tool-run-card__identity strong {
  overflow-wrap: anywhere;
  color: var(--el-text-color-primary);
  font-size: 0.875rem;
}

.tool-run-card__identity span,
.tool-run-card__status span,
.tool-run-card__muted {
  color: var(--el-text-color-secondary);
  font-size: 0.75rem;
}

.tool-run-card__status {
  display: inline-flex;
  flex: 0 0 auto;
  align-items: center;
  justify-content: flex-end;
  gap: 0.5rem;
}

.tool-run-card__body {
  min-width: 0;
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0.75rem;
}

.tool-run-card__body section {
  min-width: 0;
  display: grid;
  align-content: start;
  gap: 0.375rem;
}

.tool-run-card__body h4 {
  margin: 0;
  color: var(--el-text-color-secondary);
  font-size: 0.75rem;
  font-weight: 600;
  text-transform: uppercase;
}

.tool-runs-table__summary {
  display: flex;
  flex-wrap: wrap;
  gap: 0.25rem;
  min-width: 0;
}

.tool-runs-table__summary :deep(.el-tag) {
  max-width: 100%;
  height: auto;
  min-height: 24px;
  white-space: normal;
  overflow-wrap: anywhere;
}

@media (max-width: 720px) {
  .tool-run-card__header,
  .tool-run-card__status {
    align-items: flex-start;
    flex-direction: column;
  }

  .tool-run-card__body {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
