<script setup lang="ts">
import { computed, reactive } from 'vue';

const props = defineProps<{
  loading?: boolean;
  defaultLimit?: number;
}>();

const emit = defineEmits<{
  filter: [
    {
      analysisType?: 'deconstruct' | 'structure' | 'plot' | 'theme';
      bookId?: number;
      channelCode?: string;
      boardCode?: string;
      chapterCount?: number;
      modelName?: string;
      keyword?: string;
      startTime?: string;
      endTime?: string;
      pageSize?: number;
    }
  ];
}>();

const state = reactive({
  analysisType: '' as '' | 'deconstruct' | 'structure' | 'plot' | 'theme',
  bookId: '' as string,
  channelCode: '',
  boardCode: '',
  chapterCount: '',
  modelName: '',
  keyword: '',
  timeRange: [] as string[],
  pageSize: props.defaultLimit ?? 20,
});

const limitOptions = computed(() => [10, 20, 30, 50]);

function optionalText(value: string) {
  const normalized = value.trim();
  return normalized ? normalized : undefined;
}

function optionalNumber(value: string, allowZero = false) {
  if (!value) {
    return undefined;
  }
  const parsed = Number(value);
  return Number.isFinite(parsed) && (allowZero ? parsed >= 0 : parsed > 0) ? parsed : undefined;
}

function submit() {
  emit('filter', {
    analysisType: state.analysisType || undefined,
    bookId: optionalNumber(state.bookId),
    channelCode: optionalText(state.channelCode),
    boardCode: optionalText(state.boardCode),
    chapterCount: optionalNumber(state.chapterCount, true),
    modelName: optionalText(state.modelName),
    keyword: optionalText(state.keyword),
    startTime: state.timeRange[0],
    endTime: state.timeRange[1],
    pageSize: state.pageSize,
  });
}

function reset() {
  state.analysisType = '';
  state.bookId = '';
  state.channelCode = '';
  state.boardCode = '';
  state.chapterCount = '';
  state.modelName = '';
  state.keyword = '';
  state.timeRange = [];
  state.pageSize = props.defaultLimit ?? 20;
  submit();
}
</script>

<template>
  <el-form class="history-filter" label-position="top" @submit.prevent="submit">
    <div class="history-filter__header">
      <div>
        <p class="history-filter__eyebrow">History Replay</p>
        <h2 class="history-filter__title">历史回看</h2>
      </div>
      <p class="history-filter__subtitle">按分析类型、作品 ID 和条数快速回放已生成结果。</p>
    </div>

    <div class="history-filter__grid">
      <el-form-item label="分析类型">
        <el-select
          v-model="state.analysisType"
          placeholder="全部"
          data-test="history-filter-analysis"
        >
          <el-option label="全部" value="" />
          <el-option label="拆文分析" value="deconstruct" />
          <el-option label="结构分析" value="structure" />
          <el-option label="情节分析" value="plot" />
          <el-option label="趋势分析" value="theme" />
        </el-select>
      </el-form-item>

      <el-form-item label="作品 ID">
        <el-input
          v-model="state.bookId"
          type="number"
          min="1"
          placeholder="请输入作品 ID"
          data-test="history-filter-bookid"
        />
      </el-form-item>

      <el-form-item label="频道">
        <el-input
          v-model="state.channelCode"
          placeholder="如 male-new"
          data-test="history-filter-channel"
          clearable
        />
      </el-form-item>

      <el-form-item label="搜索内容">
        <el-input
          v-model="state.keyword"
          placeholder="书名、题材、结论、结构点"
          data-test="history-filter-keyword"
          clearable
        />
      </el-form-item>

      <el-form-item label="榜单">
        <el-input
          v-model="state.boardCode"
          placeholder="如 urban-brain"
          data-test="history-filter-board"
          clearable
        />
      </el-form-item>

      <el-form-item label="章节数">
        <el-input
          v-model="state.chapterCount"
          type="number"
          min="0"
          placeholder="全部"
          data-test="history-filter-chapter-count"
          clearable
        />
      </el-form-item>

      <el-form-item label="模型">
        <el-input
          v-model="state.modelName"
          placeholder="如 deepseek-chat"
          data-test="history-filter-model"
          clearable
        />
      </el-form-item>

      <el-form-item label="生成时间">
        <el-date-picker
          v-model="state.timeRange"
          type="datetimerange"
          value-format="YYYY-MM-DD HH:mm:ss"
          range-separator="至"
          start-placeholder="开始"
          end-placeholder="结束"
          data-test="history-filter-time"
        />
      </el-form-item>

      <el-form-item label="每页条数">
        <el-select
          v-model="state.pageSize"
          :loading="props.loading"
          data-test="history-filter-limit"
        >
          <el-option v-for="count in limitOptions" :key="count" :label="count" :value="count" />
        </el-select>
      </el-form-item>
    </div>

    <div class="history-filter__actions">
      <el-button plain native-type="button" :disabled="props.loading" @click="reset">
        重置
      </el-button>
      <el-button
        type="primary"
        native-type="submit"
        :loading="props.loading"
        data-test="history-filter-submit"
      >
        查询
      </el-button>
    </div>
  </el-form>
</template>

<style scoped lang="scss">
.history-filter {
  display: grid;
  gap: 1rem;
  border: 1px solid var(--color-border);
  border-radius: 1.25rem;
  padding: 1rem;
  background:
    linear-gradient(
      155deg,
      color-mix(in srgb, var(--color-surface-strong) 98%, transparent),
      color-mix(in srgb, var(--color-surface) 94%, transparent)
    );
  box-shadow: var(--shadow-soft);
  color: var(--color-text);
}

.history-filter__header {
  display: grid;
  gap: 0.4rem;
}

.history-filter__eyebrow,
.history-filter__title,
.history-filter__subtitle {
  margin: 0;
}

.history-filter__eyebrow {
  color: var(--color-text-muted);
  font-size: 0.8rem;
  letter-spacing: 0.14em;
  text-transform: uppercase;
}

.history-filter__title {
  font-size: 1.3rem;
}

.history-filter__subtitle {
  color: var(--color-text-muted);
  line-height: 1.6;
}

.history-filter__grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));
  gap: 1rem;
}

.history-filter__grid :deep(.el-form-item),
.history-filter__grid :deep(.el-input),
.history-filter__grid :deep(.el-select),
.history-filter__grid :deep(.el-date-editor) {
  min-width: 0;
  width: 100%;
}

.history-filter__actions {
  display: flex;
  justify-content: flex-end;
  gap: 0.75rem;
}
</style>
