<script setup lang="ts">
import { computed } from 'vue';
import { ref } from 'vue';
import type { AgentRuntimeTrace, AgentRuntimeTraceNode } from '@/types/knowledge';
import { runtimeNodeLabel } from '@/utils/knowledgeDisplay';

interface Props {
  resultJson?: string;
}

const props = defineProps<Props>();
const statusFilter = ref('all');
const selectedNode = ref<AgentRuntimeTraceNode | null>(null);

const runtimeTrace = computed<AgentRuntimeTrace | null>(() => {
  if (!props.resultJson) return null;
  try {
    const parsed = JSON.parse(props.resultJson) as { trace?: AgentRuntimeTrace };
    return parsed.trace ?? null;
  } catch {
    return null;
  }
});

const nodes = computed<AgentRuntimeTraceNode[]>(() => {
  const rawNodes = runtimeTrace.value?.nodes;
  if (!Array.isArray(rawNodes)) return [];
  return [...rawNodes].sort((left, right) => nodeOrder(left) - nodeOrder(right));
});

const executedNodes = computed(() => {
  const raw = runtimeTrace.value?.executedRuntimeNodes;
  if (!Array.isArray(raw) || raw.length === 0) {
    return new Set(
      nodes.value
        .filter((node) => ['completed', 'failed', 'running'].includes(normalizeStatus(node.status)))
        .map((node) => node.name),
    );
  }
  return new Set(raw.map(String));
});

const executedMode = computed(() => {
  const raw = runtimeTrace.value?.executedRuntimeNodes;
  return Array.isArray(raw) && raw.length > 0 ? 'real' : 'inferred';
});

const statusCounts = computed(() => {
  return nodes.value.reduce<Record<string, number>>((counts, node) => {
    const status = normalizeStatus(node.status);
    counts[status] = (counts[status] ?? 0) + 1;
    return counts;
  }, {});
});

const hasGraph = computed(() => nodes.value.length > 0);

const filteredNodes = computed(() => {
  if (statusFilter.value === 'all') return nodes.value;
  return nodes.value.filter((node) => normalizeStatus(node.status) === statusFilter.value);
});

const edges = computed(() => {
  return nodes.value.slice(0, -1).map((node, index) => ({
    from: node,
    to: nodes.value[index + 1],
  }));
});

const slowestNode = computed(() => {
  return nodes.value.reduce<AgentRuntimeTraceNode | null>((slowest, node) => {
    if (typeof node.durationMs !== 'number') return slowest;
    if (!slowest || typeof slowest.durationMs !== 'number') return node;
    return node.durationMs > slowest.durationMs ? node : slowest;
  }, null);
});

const selectedNodeJson = computed(() => {
  if (!selectedNode.value) return '';
  return JSON.stringify(selectedNode.value, null, 2);
});

function nodeOrder(node: AgentRuntimeTraceNode) {
  return typeof node.sequenceNo === 'number' ? node.sequenceNo : Number.MAX_SAFE_INTEGER;
}

function normalizeStatus(status?: string) {
  const normalized = (status || 'unknown').toLowerCase();
  if (['completed', 'failed', 'skipped', 'running'].includes(normalized)) {
    return normalized;
  }
  return 'unknown';
}

function statusType(status?: string) {
  const normalized = normalizeStatus(status);
  if (normalized === 'completed') return 'success';
  if (normalized === 'failed') return 'danger';
  if (normalized === 'skipped') return 'info';
  if (normalized === 'running') return 'warning';
  return 'info';
}

function statusLabel(status?: string) {
  const normalized = normalizeStatus(status);
  if (normalized === 'completed') return '已完成';
  if (normalized === 'failed') return '失败';
  if (normalized === 'skipped') return '已跳过';
  if (normalized === 'running') return '运行中';
  return '未知';
}

function selectStatus(status: string) {
  statusFilter.value = status;
  if (selectedNode.value && !filteredNodes.value.some((node) => node.name === selectedNode.value?.name)) {
    selectedNode.value = null;
  }
}

function selectNode(node: AgentRuntimeTraceNode) {
  selectedNode.value = node;
}

function nodeClass(node: AgentRuntimeTraceNode) {
  return `runtime-node--${normalizeStatus(node.status)}`;
}

function nodeTestId(node: AgentRuntimeTraceNode) {
  return `runtime-node-card-${node.name.replace(/[^a-zA-Z0-9_-]/g, '_')}`;
}

function durationLabel(node: AgentRuntimeTraceNode) {
  return typeof node.durationMs === 'number' ? `${node.durationMs} ms` : '-';
}

function executedLabel(node: AgentRuntimeTraceNode) {
  if (!executedNodes.value.has(node.name)) {
    return '否';
  }
  return executedMode.value === 'real' ? '是' : '兼容推断';
}
</script>

<template>
  <div class="langgraph-runtime-graph" data-test="langgraph-runtime-graph">
    <template v-if="hasGraph">
      <header class="langgraph-runtime-graph__summary">
        <el-tag size="small" type="info">{{ nodes.length }} 个节点</el-tag>
        <el-tag size="small" :type="executedMode === 'real' ? 'success' : 'warning'">
          {{ executedNodes.size }} 个{{ executedMode === 'real' ? '真实执行' : '兼容推断' }}
        </el-tag>
        <el-tag v-if="executedMode === 'inferred'" size="small" type="warning">
          旧 Trace 未记录真实执行集合
        </el-tag>
        <el-tag v-if="statusCounts.failed" size="small" type="danger">{{ statusCounts.failed }} 个失败</el-tag>
        <el-tag v-if="statusCounts.skipped" size="small" type="info">{{ statusCounts.skipped }} 个跳过</el-tag>
        <el-tag v-if="slowestNode" size="small" type="warning">
          最慢 {{ runtimeNodeLabel(slowestNode.name) }} {{ durationLabel(slowestNode) }}
        </el-tag>
      </header>

      <section v-if="edges.length" class="runtime-path" aria-label="LangGraph 运行路径">
        <h4>运行路径</h4>
        <div class="runtime-path__edges">
          <span
            v-for="edge in edges"
            :key="`${edge.from.name}-${edge.to.name}`"
            class="runtime-path__edge"
            data-test="runtime-edge"
          >
            <span :title="edge.from.name">{{ runtimeNodeLabel(edge.from.name) }}</span>
            <span class="runtime-path__connector">到</span>
            <span :title="edge.to.name">{{ runtimeNodeLabel(edge.to.name) }}</span>
          </span>
        </div>
      </section>

      <div class="runtime-filters" aria-label="运行节点状态筛选">
        <button
          type="button"
          class="runtime-filter"
          :class="{ 'runtime-filter--active': statusFilter === 'all' }"
          data-test="runtime-filter-all"
          @click="selectStatus('all')"
        >
          全部
        </button>
        <button
          type="button"
          class="runtime-filter"
          :class="{ 'runtime-filter--active': statusFilter === 'completed' }"
          data-test="runtime-filter-completed"
          @click="selectStatus('completed')"
        >
          已完成
        </button>
        <button
          type="button"
          class="runtime-filter"
          :class="{ 'runtime-filter--active': statusFilter === 'failed' }"
          data-test="runtime-filter-failed"
          @click="selectStatus('failed')"
        >
          失败
        </button>
        <button
          type="button"
          class="runtime-filter"
          :class="{ 'runtime-filter--active': statusFilter === 'skipped' }"
          data-test="runtime-filter-skipped"
          @click="selectStatus('skipped')"
        >
          已跳过
        </button>
      </div>

      <ol class="langgraph-runtime-graph__nodes" aria-label="LangGraph 运行节点">
        <li
          v-for="(node, index) in filteredNodes"
          :key="`${node.sequenceNo ?? index}-${node.name}`"
          class="runtime-node"
          :class="nodeClass(node)"
        >
          <button
            type="button"
            class="runtime-node__button"
            :data-test="nodeTestId(node)"
            @click="selectNode(node)"
          >
            <span class="runtime-node__index">{{ node.sequenceNo ?? index + 1 }}</span>
            <span class="runtime-node__body">
            <div class="runtime-node__topline">
              <strong :title="node.name">{{ runtimeNodeLabel(node.name) }}</strong>
              <el-tag size="small" :type="statusType(node.status)">
                {{ statusLabel(node.status) }}
              </el-tag>
            </div>
            <dl class="runtime-node__meta">
              <div>
                <dt>耗时</dt>
                <dd>{{ durationLabel(node) }}</dd>
              </div>
              <div>
                <dt>执行</dt>
                <dd>{{ executedLabel(node) }}</dd>
              </div>
            </dl>
            </span>
          </button>
        </li>
      </ol>

      <aside v-if="selectedNode" class="runtime-node-detail" data-test="runtime-node-detail">
        <header class="runtime-node-detail__header">
          <div>
            <h4>节点详情</h4>
            <strong :title="selectedNode.name">{{ runtimeNodeLabel(selectedNode.name) }}</strong>
          </div>
          <el-tag size="small" :type="statusType(selectedNode.status)">
            {{ statusLabel(selectedNode.status) }}
          </el-tag>
        </header>
        <dl class="runtime-node-detail__meta">
          <div>
            <dt>顺序</dt>
            <dd>{{ selectedNode.sequenceNo ?? '-' }}</dd>
          </div>
          <div>
            <dt>耗时</dt>
            <dd>{{ durationLabel(selectedNode) }}</dd>
          </div>
          <div>
            <dt>执行</dt>
            <dd>{{ executedLabel(selectedNode) }}</dd>
          </div>
        </dl>
        <p v-if="selectedNode.error" class="runtime-node-detail__error">{{ selectedNode.error }}</p>
        <h5>节点原始 JSON</h5>
        <pre class="runtime-node-detail__json">{{ selectedNodeJson }}</pre>
      </aside>
    </template>

    <p v-else class="langgraph-runtime-graph__empty">暂无 LangGraph 运行数据</p>
  </div>
</template>

<style scoped>
.langgraph-runtime-graph {
  display: grid;
  gap: 0.875rem;
  min-width: 0;
}

.langgraph-runtime-graph__summary {
  display: flex;
  flex-wrap: wrap;
  gap: 0.5rem;
}

.langgraph-runtime-graph__nodes {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(13.5rem, 1fr));
  gap: 0.75rem;
  min-width: 0;
  margin: 0;
  padding: 0;
  list-style: none;
}

.runtime-path {
  display: grid;
  gap: 0.5rem;
  min-width: 0;
}

.runtime-path h4 {
  margin: 0;
  font-size: 0.875rem;
}

.runtime-path__edges {
  display: flex;
  flex-wrap: wrap;
  gap: 0.5rem;
  min-width: 0;
}

.runtime-path__edge {
  min-width: 0;
  display: inline-flex;
  align-items: center;
  gap: 0.375rem;
  padding: 0.375rem 0.5rem;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  background: var(--el-fill-color-extra-light);
  color: var(--el-text-color-regular);
  font-family: var(--el-font-family-mono, 'Courier New', monospace);
  font-size: 0.75rem;
  overflow-wrap: anywhere;
}

.runtime-path__connector {
  color: var(--el-text-color-secondary);
  font-family: inherit;
}

.runtime-filters {
  display: flex;
  flex-wrap: wrap;
  gap: 0.5rem;
}

.runtime-filter {
  min-height: 2rem;
  padding: 0.25rem 0.75rem;
  border: 1px solid var(--el-border-color);
  border-radius: 6px;
  background: var(--el-bg-color);
  color: var(--el-text-color-regular);
  cursor: pointer;
}

.runtime-filter:hover,
.runtime-filter--active {
  border-color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
  color: var(--el-color-primary);
}

.runtime-node {
  position: relative;
  min-width: 0;
  min-height: 6.5rem;
  border: 1px solid var(--el-border-color-lighter);
  border-left: 4px solid var(--el-border-color);
  border-radius: 6px;
  background: var(--el-fill-color-extra-light);
}

.runtime-node--completed {
  border-left-color: var(--el-color-success);
}

.runtime-node--failed {
  border-left-color: var(--el-color-danger);
}

.runtime-node--skipped {
  border-left-color: var(--el-color-info);
}

.runtime-node--running {
  border-left-color: var(--el-color-warning);
}

.runtime-node__button {
  width: 100%;
  min-height: 6.5rem;
  display: grid;
  grid-template-columns: 2rem minmax(0, 1fr);
  gap: 0.625rem;
  padding: 0.75rem;
  border: 0;
  background: transparent;
  color: inherit;
  text-align: left;
  cursor: pointer;
}

.runtime-node__button:hover {
  background: color-mix(in srgb, var(--el-color-primary-light-9) 64%, transparent);
}

.runtime-node__button:focus-visible {
  outline: 2px solid var(--el-color-primary);
  outline-offset: 2px;
}

.runtime-node__index {
  display: grid;
  place-items: center;
  width: 2rem;
  height: 2rem;
  border-radius: 999px;
  background: var(--el-bg-color);
  color: var(--el-text-color-secondary);
  font-size: 0.8125rem;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
}

.runtime-node__body {
  min-width: 0;
  display: grid;
  align-content: start;
  gap: 0.625rem;
}

.runtime-node__topline {
  min-width: 0;
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 0.5rem;
}

.runtime-node__topline strong {
  min-width: 0;
  color: var(--el-text-color-primary);
  font-family: var(--el-font-family-mono, 'Courier New', monospace);
  font-size: 0.875rem;
  line-height: 1.4;
  overflow-wrap: anywhere;
}

.runtime-node__meta {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0.5rem;
  margin: 0;
}

.runtime-node__meta div {
  min-width: 0;
}

.runtime-node__meta dt {
  color: var(--el-text-color-secondary);
  font-size: 0.75rem;
}

.runtime-node__meta dd {
  margin: 0.125rem 0 0;
  color: var(--el-text-color-primary);
  font-size: 0.8125rem;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}

.langgraph-runtime-graph__empty {
  margin: 0;
  color: var(--el-text-color-secondary);
  font-style: italic;
}

.runtime-node-detail {
  display: grid;
  gap: 0.75rem;
  min-width: 0;
  padding: 0.875rem;
  border: 1px solid var(--el-border-color);
  border-radius: 6px;
  background: var(--el-bg-color);
}

.runtime-node-detail__header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 0.75rem;
}

.runtime-node-detail__header h4,
.runtime-node-detail__header strong,
.runtime-node-detail h5,
.runtime-node-detail__error {
  margin: 0;
}

.runtime-node-detail__header h4 {
  color: var(--el-text-color-secondary);
  font-size: 0.75rem;
}

.runtime-node-detail__header strong {
  display: block;
  margin-top: 0.125rem;
  font-family: var(--el-font-family-mono, 'Courier New', monospace);
  overflow-wrap: anywhere;
}

.runtime-node-detail__meta {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 0.5rem;
  margin: 0;
}

.runtime-node-detail__meta dt {
  color: var(--el-text-color-secondary);
  font-size: 0.75rem;
}

.runtime-node-detail__meta dd {
  margin: 0.125rem 0 0;
  font-size: 0.8125rem;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}

.runtime-node-detail__error {
  padding: 0.5rem 0.625rem;
  border: 1px solid var(--el-color-danger-light-5);
  border-radius: 4px;
  background: var(--el-color-danger-light-9);
  color: var(--el-color-danger);
  overflow-wrap: anywhere;
}

.runtime-node-detail__json {
  margin: 0;
  max-height: 16rem;
  overflow: auto;
  padding: 0.75rem;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 4px;
  background: var(--el-fill-color-light);
  font-size: 0.8125rem;
  line-height: 1.5;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

@media (max-width: 560px) {
  .langgraph-runtime-graph__nodes {
    grid-template-columns: minmax(0, 1fr);
  }

  .runtime-node-detail__meta {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
