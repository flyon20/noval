<script setup lang="ts">
import { computed } from 'vue';

interface Props {
  evidencePackJson?: string;
}

const props = defineProps<Props>();

const evidencePack = computed(() => {
  if (!props.evidencePackJson) return null;
  try {
    return JSON.parse(props.evidencePackJson);
  } catch {
    return null;
  }
});

const summary = computed(() => {
  if (!evidencePack.value) return null;
  return {
    factCount: evidencePack.value.factCount || 0,
    exampleCount: evidencePack.value.exampleCount || 0,
    signalCount: evidencePack.value.signalCount || 0,
    inferenceSeedCount: evidencePack.value.inferenceSeedCount || 0,
    facts: evidencePack.value.facts || [],
    examples: evidencePack.value.examples || [],
    signals: evidencePack.value.signals || [],
  };
});
</script>

<template>
  <div class="evidence-pack-summary">
    <template v-if="!summary">
      <p class="evidence-pack-summary__empty">暂无证据包数据</p>
    </template>
    <template v-else>
      <el-row :gutter="16" class="evidence-pack-summary__stats">
        <el-col :span="6">
          <el-statistic title="事实" :value="summary.factCount" />
        </el-col>
        <el-col :span="6">
          <el-statistic title="示例" :value="summary.exampleCount" />
        </el-col>
        <el-col :span="6">
          <el-statistic title="趋势信号" :value="summary.signalCount" />
        </el-col>
        <el-col :span="6">
          <el-statistic title="推演种子" :value="summary.inferenceSeedCount" />
        </el-col>
      </el-row>

      <el-collapse v-if="summary.factCount > 0 || summary.exampleCount > 0" accordion class="evidence-pack-summary__details">
        <el-collapse-item v-if="summary.facts.length" title="事实" name="facts">
          <ul class="evidence-list">
            <li v-for="(fact, idx) in summary.facts" :key="idx">
              <strong v-if="fact.ref">{{ fact.ref }}:</strong> {{ fact.claim || fact.summary || JSON.stringify(fact) }}
            </li>
          </ul>
        </el-collapse-item>
        <el-collapse-item v-if="summary.examples.length" title="示例" name="examples">
          <ul class="evidence-list">
            <li v-for="(example, idx) in summary.examples" :key="idx">
              <strong v-if="example.ref">{{ example.ref }}:</strong> {{ example.excerpt || example.summary || JSON.stringify(example) }}
            </li>
          </ul>
        </el-collapse-item>
        <el-collapse-item v-if="summary.signals.length" title="趋势信号" name="signals">
          <ul class="evidence-list">
            <li v-for="(signal, idx) in summary.signals" :key="idx">
              {{ signal.pattern || signal.summary || JSON.stringify(signal) }}
            </li>
          </ul>
        </el-collapse-item>
      </el-collapse>
    </template>
  </div>
</template>

<style scoped>
.evidence-pack-summary {
  display: grid;
  gap: 1rem;
}

.evidence-pack-summary__empty {
  color: var(--el-text-color-secondary);
  font-style: italic;
  margin: 0;
}

.evidence-pack-summary__stats {
  margin-bottom: 1rem;
}

.evidence-list {
  margin: 0;
  padding-left: 1.5rem;
}

.evidence-list li {
  margin-bottom: 0.5rem;
  line-height: 1.5;
}
</style>
