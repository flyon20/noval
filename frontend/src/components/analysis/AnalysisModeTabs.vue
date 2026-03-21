<script setup lang="ts">
const modes = [
  { key: 'deconstruct', label: '拆文' },
  { key: 'structure', label: '结构' },
  { key: 'plot', label: '情节' },
] as const;

const props = defineProps<{
  modelValue: 'deconstruct' | 'structure' | 'plot';
}>();

const emit = defineEmits<{
  'update:modelValue': [value: 'deconstruct' | 'structure' | 'plot'];
}>();

function selectMode(mode: typeof props.modelValue) {
  if (mode !== props.modelValue) {
    emit('update:modelValue', mode);
  }
}
</script>

<template>
  <div class="analysis-tabs">
    <button
      v-for="mode in modes"
      :key="mode.key"
      type="button"
      data-test="analysis-tab"
      :class="['analysis-tab', { 'is-active': mode.key === props.modelValue }]"
      :aria-pressed="mode.key === props.modelValue ? 'true' : 'false'"
      @click="selectMode(mode.key)"
    >
      {{ mode.label }}
    </button>
  </div>
</template>

<style scoped lang="scss">
.analysis-tabs {
  display: flex;
  gap: 0.35rem;
}

.analysis-tab {
  padding: 0.65rem 1.2rem;
  border-radius: 999px;
  border: 1px solid transparent;
  background: rgba(255, 255, 255, 0.85);
  font-weight: 500;
  color: var(--color-text);
  transition: background 0.25s ease, border 0.25s ease;
}

.analysis-tab.is-active {
  background: rgba(210, 136, 61, 0.12);
  border-color: rgba(210, 136, 61, 0.4);
  color: var(--color-primary);
}

.analysis-tab:hover {
  border-color: rgba(35, 65, 58, 0.2);
}
</style>
