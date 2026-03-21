<script setup lang="ts">
import { computed, reactive } from 'vue';

const props = defineProps<{
  loading?: boolean;
  defaultLimit?: number;
}>();

const emit = defineEmits<{
  filter: [
    { analysisType?: 'deconstruct' | 'structure' | 'plot' | 'theme'; bookId?: number; limit?: number }
  ];
}>();

const state = reactive({
  analysisType: '' as '' | 'deconstruct' | 'structure' | 'plot' | 'theme',
  bookId: '' as string,
  limit: props.defaultLimit ?? 20,
});

const limitOptions = computed(() => [10, 20, 30, 50]);

function submit() {
  emit('filter', {
    analysisType: state.analysisType || undefined,
    bookId: state.bookId ? Number(state.bookId) : undefined,
    limit: state.limit,
  });
}

function reset() {
  state.analysisType = '';
  state.bookId = '';
  state.limit = props.defaultLimit ?? 20;
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

      <el-form-item label="返回条数">
        <el-select
          v-model="state.limit"
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
  background: rgba(255, 255, 255, 0.88);
  box-shadow: var(--shadow-soft);
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

.history-filter__actions {
  display: flex;
  justify-content: flex-end;
  gap: 0.75rem;
}
</style>
