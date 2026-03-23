<script setup lang="ts">
import type { AnalysisType } from '@/types/analysis';

type AnalysisTabStatusTone = 'idle' | 'running' | 'done' | 'error';

interface AnalysisTabStatus {
  phaseLabel?: string;
  tone?: AnalysisTabStatusTone;
}

const modes: Array<{ key: AnalysisType; label: string }> = [
  { key: 'deconstruct', label: '\u62c6\u6587' },
  { key: 'structure', label: '\u7ed3\u6784' },
  { key: 'plot', label: '\u60c5\u8282' },
];

const props = defineProps<{
  modelValue: AnalysisType;
  statusByMode?: Partial<Record<AnalysisType, AnalysisTabStatus>>;
}>();

const emit = defineEmits<{
  'update:modelValue': [value: AnalysisType];
}>();

function selectMode(mode: AnalysisType) {
  if (mode !== props.modelValue) {
    emit('update:modelValue', mode);
  }
}

function resolveToneClass(mode: AnalysisType) {
  return props.statusByMode?.[mode]?.tone ?? 'idle';
}
</script>

<template>
  <div class="analysis-tabs" data-test="analysis-tabs">
    <button
      v-for="mode in modes"
      :key="mode.key"
      type="button"
      data-test="analysis-tab"
      :data-mode="mode.key"
      :class="['analysis-tab', { 'is-active': mode.key === props.modelValue }]"
      :aria-pressed="mode.key === props.modelValue ? 'true' : 'false'"
      @click="selectMode(mode.key)"
    >
      <span class="analysis-tab__label">{{ mode.label }}</span>
      <span
        v-if="props.statusByMode?.[mode.key]?.phaseLabel"
        class="analysis-tab__meta"
        :class="`is-${resolveToneClass(mode.key)}`"
      >
        {{ props.statusByMode?.[mode.key]?.phaseLabel }}
      </span>
    </button>
  </div>
</template>

<style scoped lang="scss">
.analysis-tabs {
  display: flex;
  gap: 0.5rem;
  overflow-x: auto;
  padding-bottom: 0.2rem;
  scrollbar-width: thin;
}

.analysis-tab {
  flex: 0 0 auto;
  min-width: 7rem;
  display: grid;
  gap: 0.2rem;
  padding: 0.7rem 1rem;
  border-radius: 1rem;
  border: 1px solid transparent;
  background: rgba(255, 255, 255, 0.85);
  font-weight: 500;
  color: var(--color-text);
  text-align: left;
  transition: background 0.25s ease, border 0.25s ease, transform 0.25s ease;
}

.analysis-tab.is-active {
  background: rgba(210, 136, 61, 0.12);
  border-color: rgba(210, 136, 61, 0.4);
  color: var(--color-primary);
}

.analysis-tab:hover {
  border-color: rgba(35, 65, 58, 0.2);
}

.analysis-tab__label {
  font-size: 0.98rem;
  line-height: 1.2;
}

.analysis-tab__meta {
  font-size: 0.74rem;
  color: var(--color-text-muted);
  line-height: 1.2;
}

.analysis-tab__meta.is-running {
  color: var(--color-primary);
}

.analysis-tab__meta.is-done {
  color: #2f7d4d;
}

.analysis-tab__meta.is-error {
  color: var(--color-danger);
}

@media (max-width: 768px) {
  .analysis-tabs {
    gap: 0.45rem;
    margin-inline: -0.125rem;
    padding-inline: 0.125rem;
  }

  .analysis-tab {
    min-width: 6.25rem;
    padding: 0.65rem 0.85rem;
    border-radius: 0.9rem;
  }

  .analysis-tab__label {
    font-size: 0.92rem;
  }

  .analysis-tab__meta {
    font-size: 0.7rem;
  }
}
</style>
