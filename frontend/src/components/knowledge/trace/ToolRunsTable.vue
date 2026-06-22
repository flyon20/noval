<script setup lang="ts">
import { computed } from 'vue';

interface Props {
  toolRunsJson?: string;
}

const props = defineProps<Props>();

const toolRuns = computed(() => {
  if (!props.toolRunsJson) return [];
  try {
    const parsed = JSON.parse(props.toolRunsJson);
    return Array.isArray(parsed) ? parsed : [];
  } catch {
    return [];
  }
});

const getStatusType = (status: string) => {
  if (status === 'succeeded') return 'success';
  if (status === 'failed') return 'danger';
  if (status === 'skipped') return 'info';
  return 'info';
};
</script>

<template>
  <div class="tool-runs-table">
    <template v-if="!toolRuns.length">
      <p class="tool-runs-table__empty">No tool runs recorded</p>
    </template>
    <template v-else>
      <el-table :data="toolRuns" size="small" border>
        <el-table-column prop="name" label="Tool" min-width="180" />
        <el-table-column label="Status" width="100">
          <template #default="{ row }">
            <el-tag :type="getStatusType(row.status)" size="small">
              {{ row.status }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="toolset" label="Toolset" width="100" />
        <el-table-column prop="resultCount" label="Results" width="80" align="right" />
        <el-table-column prop="errorType" label="Error" width="120">
          <template #default="{ row }">
            <el-text v-if="row.errorType" type="danger" size="small">{{ row.errorType }}</el-text>
          </template>
        </el-table-column>
      </el-table>
    </template>
  </div>
</template>

<style scoped>
.tool-runs-table__empty {
  color: var(--el-text-color-secondary);
  font-style: italic;
  margin: 0;
}
</style>
