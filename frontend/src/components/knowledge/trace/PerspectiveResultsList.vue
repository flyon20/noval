<script setup lang="ts">
import { computed } from 'vue';

interface Props {
  perspectiveResultsJson?: string;
}

const props = defineProps<Props>();

const perspectives = computed(() => {
  if (!props.perspectiveResultsJson) return [];
  try {
    const parsed = JSON.parse(props.perspectiveResultsJson);
    return Array.isArray(parsed) ? parsed : [];
  } catch {
    return [];
  }
});
</script>

<template>
  <div class="perspective-results-list">
    <template v-if="!perspectives.length">
      <p class="perspective-results-list__empty">No PerspectiveResults data</p>
    </template>
    <template v-else>
      <el-timeline>
        <el-timeline-item
          v-for="(result, idx) in perspectives"
          :key="idx"
          :timestamp="result.taskType"
          placement="top"
        >
          <el-card shadow="hover">
            <template #header>
              <div class="perspective-header">
                <el-tag type="primary" size="small">{{ result.perspective }}</el-tag>
                <el-tag type="info" size="small">{{ result.taskType }}</el-tag>
              </div>
            </template>
            <p class="perspective-summary">{{ result.summary }}</p>
            <el-descriptions v-if="result.evidenceRefs?.length" :column="1" size="small">
              <el-descriptions-item label="Evidence">
                <el-tag
                  v-for="ref in result.evidenceRefs"
                  :key="ref"
                  size="small"
                  class="evidence-ref-tag"
                >
                  {{ ref }}
                </el-tag>
              </el-descriptions-item>
            </el-descriptions>
          </el-card>
        </el-timeline-item>
      </el-timeline>
    </template>
  </div>
</template>

<style scoped>
.perspective-results-list__empty {
  color: var(--el-text-color-secondary);
  font-style: italic;
  margin: 0;
}

.perspective-header {
  display: flex;
  gap: 0.5rem;
  align-items: center;
}

.perspective-summary {
  margin: 0;
  line-height: 1.6;
}

.evidence-ref-tag {
  margin-right: 0.25rem;
  margin-bottom: 0.25rem;
}
</style>
