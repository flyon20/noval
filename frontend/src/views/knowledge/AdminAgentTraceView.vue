<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { knowledgeApi } from '@/api/knowledge';
import type { AgentTraceSummary } from '@/types/knowledge';
import TaskGraphDisplay from '@/components/knowledge/trace/TaskGraphDisplay.vue';
import ToolRunsTable from '@/components/knowledge/trace/ToolRunsTable.vue';
import EvidencePackSummary from '@/components/knowledge/trace/EvidencePackSummary.vue';
import PerspectiveResultsList from '@/components/knowledge/trace/PerspectiveResultsList.vue';

defineOptions({
  name: 'AdminAgentTraceView',
});

const traces = ref<AgentTraceSummary[]>([]);
const selected = ref<AgentTraceSummary | null>(null);
const loading = ref(false);
const activeNames = ref([
  'taskGraph',
  'toolRuns',
  'evidencePack',
  'intentDecision',
  'sourcePolicy',
  'contextUsed',
  'memoryUsed',
  'supervisorDecision',
  'memoryCandidates',
]);

onMounted(loadTraces);

async function loadTraces() {
  loading.value = true;
  try {
    const response = await knowledgeApi.listAgentTraces();
    traces.value = response.data.data ?? [];
    if (traces.value.length) {
      await selectTrace(traces.value[0]);
    }
  } finally {
    loading.value = false;
  }
}

async function selectTrace(trace: AgentTraceSummary) {
  const response = await knowledgeApi.getAgentTrace(trace.id);
  selected.value = response.data.data;
}

function hasJsonSection(value?: string) {
  return Boolean(value && value.trim() && value.trim() !== 'null');
}

function formatJson(value?: string) {
  if (!value) return '';
  try {
    return JSON.stringify(JSON.parse(value), null, 2);
  } catch {
    return value;
  }
}
</script>

<template>
  <main class="admin-agent-trace">
    <aside class="admin-agent-trace__list">
      <el-table
        v-loading="loading"
        :data="traces"
        size="small"
        height="100%"
        @row-click="selectTrace"
      >
        <el-table-column prop="traceId" label="Trace" min-width="150" />
        <el-table-column prop="status" label="Status" width="110" />
      </el-table>
    </aside>
    <section class="admin-agent-trace__detail" data-test="agent-trace-detail">
      <template v-if="selected">
        <div class="trace-header">
          <h1 class="trace-header__id">{{ selected.traceId }}</h1>
          <el-tag v-if="selected.status" type="info">{{ selected.status }}</el-tag>
          <el-tag v-if="selected.snapshotTime" type="success">Snapshot {{ selected.snapshotTime }}</el-tag>
        </div>
        <el-card v-if="selected.question" shadow="never" class="trace-question">
          <template #header>Question</template>
          <p>{{ selected.question }}</p>
        </el-card>

        <el-collapse v-model="activeNames" class="trace-sections">
          <el-collapse-item title="TaskGraph" name="taskGraph">
            <TaskGraphDisplay :task-graph-json="selected.taskGraph" />
          </el-collapse-item>

          <el-collapse-item title="Tool Runs" name="toolRuns">
            <ToolRunsTable :tool-runs-json="selected.toolRuns" />
          </el-collapse-item>

          <el-collapse-item title="Evidence Pack" name="evidencePack">
            <EvidencePackSummary :evidence-pack-json="selected.evidencePack" />
          </el-collapse-item>

          <el-collapse-item title="Perspective Results" name="perspectiveResults">
            <PerspectiveResultsList :perspective-results-json="selected.perspectiveResults" />
          </el-collapse-item>

          <el-collapse-item v-if="hasJsonSection(selected.intentDecision)" title="Intent Decision" name="intentDecision">
            <pre class="trace-raw-json">{{ formatJson(selected.intentDecision) }}</pre>
          </el-collapse-item>

          <el-collapse-item v-if="hasJsonSection(selected.sourcePolicy)" title="Source Policy" name="sourcePolicy">
            <pre class="trace-raw-json">{{ formatJson(selected.sourcePolicy) }}</pre>
          </el-collapse-item>

          <el-collapse-item v-if="hasJsonSection(selected.contextUsed)" title="Context Used" name="contextUsed">
            <pre class="trace-raw-json">{{ formatJson(selected.contextUsed) }}</pre>
          </el-collapse-item>

          <el-collapse-item v-if="hasJsonSection(selected.memoryUsed)" title="Memory Used" name="memoryUsed">
            <pre class="trace-raw-json">{{ formatJson(selected.memoryUsed) }}</pre>
          </el-collapse-item>

          <el-collapse-item
            v-if="hasJsonSection(selected.supervisorDecision)"
            title="Supervisor Decision"
            name="supervisorDecision"
          >
            <pre class="trace-raw-json">{{ formatJson(selected.supervisorDecision) }}</pre>
          </el-collapse-item>

          <el-collapse-item
            v-if="hasJsonSection(selected.memoryCandidates)"
            title="Memory Candidates"
            name="memoryCandidates"
          >
            <pre class="trace-raw-json">{{ formatJson(selected.memoryCandidates) }}</pre>
          </el-collapse-item>

          <el-collapse-item v-if="selected.resultJson" title="Raw JSON" name="raw">
            <pre class="trace-raw-json">{{ selected.resultJson }}</pre>
          </el-collapse-item>
        </el-collapse>
      </template>
      <el-empty v-else description="Select a trace to view details" />
    </section>
  </main>
</template>

<style scoped>
.admin-agent-trace {
  height: calc(100dvh - 4rem);
  display: grid;
  grid-template-columns: minmax(18rem, 24rem) minmax(0, 1fr);
  gap: 1rem;
  padding: 1rem;
}

.admin-agent-trace__list,
.admin-agent-trace__detail {
  min-height: 0;
  overflow: auto;
}

.admin-agent-trace__detail {
  display: grid;
  align-content: start;
  gap: 1rem;
}

.trace-header {
  display: flex;
  align-items: center;
  gap: 1rem;
}

.trace-header__id {
  margin: 0;
  font-size: 1.25rem;
}

.trace-question {
  background: var(--el-fill-color-lighter);
}

.trace-question p {
  margin: 0;
  line-height: 1.6;
}

.trace-sections {
  border: none;
}

.trace-raw-json {
  margin: 0;
  padding: 0.75rem;
  border: 1px solid var(--el-border-color);
  border-radius: 4px;
  overflow: auto;
  background: var(--el-fill-color-light);
  font-size: 0.875rem;
  max-height: 400px;
}
</style>
