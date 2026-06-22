<script setup lang="ts">
import { computed } from 'vue';

interface Props {
  taskGraphJson?: string;
}

const props = defineProps<Props>();

const taskGraph = computed(() => {
  if (!props.taskGraphJson) return null;
  try {
    return JSON.parse(props.taskGraphJson);
  } catch {
    return null;
  }
});

const tasks = computed(() => {
  return taskGraph.value?.tasks || [];
});

const getPerspectiveColor = (perspective: string) => {
  const colors: Record<string, string> = {
    market: '#409eff',
    book: '#67c23a',
    editor: '#e6a23c',
    author: '#f56c6c',
    reader: '#909399',
  };
  return colors[perspective] || '#909399';
};
</script>

<template>
  <div class="task-graph-display">
    <template v-if="!taskGraph">
      <p class="task-graph-display__empty">No TaskGraph data</p>
    </template>
    <template v-else>
      <div class="task-graph-display__header">
        <el-tag type="info" size="small">{{ taskGraph.schemaVersion }}</el-tag>
        <el-tag v-if="taskGraph.answerBoundary" size="small">
          {{ taskGraph.answerBoundary }}
        </el-tag>
      </div>
      <p v-if="taskGraph.userGoal" class="task-graph-display__goal">
        <strong>User Goal:</strong> {{ taskGraph.userGoal }}
      </p>
      <div class="task-graph-display__tasks">
        <el-card
          v-for="task in tasks"
          :key="task.id"
          shadow="hover"
          class="task-graph-display__task-card"
        >
          <template #header>
            <div class="task-card-header">
              <span class="task-card-header__id">{{ task.id }}</span>
              <el-tag :color="getPerspectiveColor(task.perspective)" size="small">
                {{ task.perspective }}
              </el-tag>
              <el-tag type="info" size="small">{{ task.type }}</el-tag>
            </div>
          </template>
          <p class="task-card__goal">{{ task.goal }}</p>
          <el-descriptions v-if="task.tools?.length || task.dependsOn?.length" :column="1" size="small">
            <el-descriptions-item v-if="task.tools?.length" label="Tools">
              <el-tag v-for="tool in task.tools" :key="tool" size="small" class="task-card__tool-tag">
                {{ tool }}
              </el-tag>
            </el-descriptions-item>
            <el-descriptions-item v-if="task.dependsOn?.length" label="Depends On">
              <el-tag v-for="dep in task.dependsOn" :key="dep" type="warning" size="small">
                {{ dep }}
              </el-tag>
            </el-descriptions-item>
          </el-descriptions>
        </el-card>
      </div>
    </template>
  </div>
</template>

<style scoped>
.task-graph-display {
  display: grid;
  gap: 1rem;
}

.task-graph-display__empty {
  color: var(--el-text-color-secondary);
  font-style: italic;
}

.task-graph-display__header {
  display: flex;
  gap: 0.5rem;
  flex-wrap: wrap;
}

.task-graph-display__goal {
  margin: 0;
  padding: 0.75rem;
  background: var(--el-fill-color-light);
  border-radius: 4px;
}

.task-graph-display__tasks {
  display: grid;
  gap: 0.75rem;
}

.task-card-header {
  display: flex;
  gap: 0.5rem;
  align-items: center;
}

.task-card-header__id {
  font-weight: 600;
  font-family: var(--el-font-family-mono, 'Courier New', monospace);
}

.task-card__goal {
  margin: 0 0 0.5rem;
}

.task-card__tool-tag {
  margin-right: 0.25rem;
  margin-bottom: 0.25rem;
}
</style>
