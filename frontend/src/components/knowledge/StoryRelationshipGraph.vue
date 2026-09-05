<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue';
import { GraphChart } from 'echarts/charts';
import { LegendComponent, TooltipComponent } from 'echarts/components';
import { CanvasRenderer } from 'echarts/renderers';
import { use } from 'echarts/core';
import VChart from 'vue-echarts';
import { Document, Refresh } from '@element-plus/icons-vue';
import { knowledgeApi } from '@/api/knowledge';
import type { StoryGraphEdge, StoryGraphNode, StoryGraphResult } from '@/types/knowledge';

use([GraphChart, LegendComponent, TooltipComponent, CanvasRenderer]);

const props = defineProps<{
  projectId: number | null;
  workId: number | null;
}>();

const emit = defineEmits<{
  error: [message: string];
  selectNode: [node: StoryGraphNode | null];
  evidenceNavigate: [chapterId: number];
}>();

const TYPE_FILTERS = [
  { value: 'ALL', label: '全部' },
  { value: 'CHARACTER', label: '人物' },
  { value: 'FACTION', label: '势力' },
  { value: 'LOCATION', label: '地点' },
  { value: 'ITEM', label: '道具' },
  { value: 'EVENT', label: '事件' },
  { value: 'FORESHADOWING', label: '伏笔' },
  { value: 'RULE', label: '规则' },
];

const loading = ref(false);
const errorMessage = ref('');
const typeFilter = ref('ALL');
const graph = ref<StoryGraphResult | null>(null);
const selectedNode = ref<StoryGraphNode | null>(null);
let loadGeneration = 0;

const typeOptions = TYPE_FILTERS;

const filteredNodes = computed(() => {
  const nodes = graph.value?.nodes || [];
  if (typeFilter.value === 'ALL') {
    return nodes;
  }
  return nodes.filter((node) => {
    const type = String(node.nodeType || node.category || '').toUpperCase();
    if (typeFilter.value === 'CHARACTER') {
      return type === 'CHARACTER' || type === 'PERSON';
    }
    if (typeFilter.value === 'FACTION') {
      return type === 'FACTION' || type === 'ORGANIZATION';
    }
    if (typeFilter.value === 'ITEM') {
      return type === 'ITEM' || type === 'PROP';
    }
    if (typeFilter.value === 'FORESHADOWING') {
      return type === 'FORESHADOWING' || type === 'FORESHADOW';
    }
    if (typeFilter.value === 'RULE') {
      return type === 'RULE' || type === 'WORLD_RULE' || type === 'SETTING_RULE';
    }
    return type === typeFilter.value;
  });
});

const filteredEdges = computed(() => {
  const allowed = new Set(
    filteredNodes.value.map((node) => String(node.nodeId ?? node.id ?? '')).filter(Boolean),
  );
  return (graph.value?.edges || []).filter((edge) => {
    const source = String(edge.source ?? edge.fromNodeId ?? '');
    const target = String(edge.target ?? edge.toNodeId ?? '');
    return allowed.has(source) && allowed.has(target);
  });
});

const nodeLabels = computed(() => new Map(
  (graph.value?.nodes || []).map((node) => [String(node.nodeId ?? node.id ?? ''), nodeLabel(node)]),
));

const chartOption = computed(() => {
  const rawCategories = Array.from(
    new Set(filteredNodes.value.map((node) => String(node.nodeType || node.category || 'OTHER'))),
  );
  const categories = rawCategories.map((name) => ({ name: nodeTypeValueLabel(name) }));
  const categoryIndex = new Map(rawCategories.map((name, index) => [name, index]));
  return {
    tooltip: {},
    legend: categories.length
      ? [{ data: categories.map((item) => item.name), bottom: 0, type: 'scroll' }]
      : undefined,
    series: [
      {
        type: 'graph',
        layout: 'force',
        roam: true,
        draggable: true,
        left: 54,
        right: 54,
        top: 30,
        bottom: 60,
        categories,
        label: {
          show: true,
          position: 'bottom',
          formatter: '{b}',
          width: 100,
          overflow: 'break',
          align: 'center',
          lineHeight: 14,
        },
        force: { repulsion: 120, edgeLength: 80 },
        data: filteredNodes.value.map((node) => {
          const type = String(node.nodeType || node.category || 'OTHER');
          return {
            id: String(node.nodeId ?? node.id ?? ''),
            name: String(node.displayName || node.name || node.nodeId || '节点'),
            category: categoryIndex.get(type) ?? 0,
            value: node.confidence ?? 1,
            raw: node,
          };
        }),
        links: filteredEdges.value.map((edge) => ({
          source: String(edge.source ?? edge.fromNodeId ?? ''),
          target: String(edge.target ?? edge.toNodeId ?? ''),
          name: String(edge.relationType || edge.name || ''),
          raw: edge,
        })),
        lineStyle: { color: 'source', curveness: 0.15 },
        emphasis: { focus: 'adjacency' },
      },
    ],
  };
});

const relationList = computed(() => {
  if (!selectedNode.value) {
    return filteredEdges.value.slice(0, 40);
  }
  const selectedId = String(selectedNode.value.nodeId ?? selectedNode.value.id ?? '');
  return filteredEdges.value.filter((edge) => {
    const source = String(edge.source ?? edge.fromNodeId ?? '');
    const target = String(edge.target ?? edge.toNodeId ?? '');
    return source === selectedId || target === selectedId;
  });
});

const hasData = computed(() => filteredNodes.value.length > 0);

function canUseCanvas() {
  if (typeof window === 'undefined' || typeof ResizeObserver === 'undefined') {
    return false;
  }
  try {
    const canvas = document.createElement('canvas');
    return Boolean(canvas.getContext?.('2d'));
  } catch {
    return false;
  }
}

const shouldRenderChart = computed(() => hasData.value && canUseCanvas());

watch(
  () => [props.projectId, props.workId] as const,
  () => {
    selectedNode.value = null;
    emit('selectNode', null);
    void loadGraph();
  },
  { immediate: true },
);

onBeforeUnmount(() => {
  selectedNode.value = null;
});

async function loadGraph() {
  const projectId = props.projectId;
  const workId = props.workId;
  const generation = ++loadGeneration;
  if (!projectId || !workId) {
    graph.value = null;
    return;
  }
  graph.value = null;
  loading.value = true;
  errorMessage.value = '';
  try {
    const response = await knowledgeApi.getStoryGraph(projectId, workId, { nodeLimit: 60 });
    if (generation !== loadGeneration || projectId !== props.projectId || workId !== props.workId) {
      return;
    }
    graph.value = response.data.data ?? { nodes: [], edges: [] };
  } catch {
    if (generation === loadGeneration && projectId === props.projectId && workId === props.workId) {
      graph.value = null;
      errorMessage.value = '关系图加载失败';
      emit('error', errorMessage.value);
    }
  } finally {
    if (generation === loadGeneration && projectId === props.projectId && workId === props.workId) {
      loading.value = false;
    }
  }
}

function onChartClick(params: { dataType?: string; data?: { raw?: StoryGraphNode } }) {
  if (params?.dataType === 'node' && params.data?.raw) {
    selectedNode.value = params.data.raw;
    emit('selectNode', selectedNode.value);
  }
}

function selectFromList(node: StoryGraphNode) {
  selectedNode.value = node;
  emit('selectNode', node);
}

function nodeLabel(node: StoryGraphNode) {
  return String(node.displayName || node.name || node.nodeId || '节点');
}

function nodeTypeLabel(node: StoryGraphNode) {
  return nodeTypeValueLabel(String(node.nodeType || node.category || ''));
}

function nodeTypeValueLabel(value: string) {
  const type = value.toUpperCase();
  const labels: Record<string, string> = {
    CHARACTER: '人物',
    PERSON: '人物',
    FACTION: '势力',
    ORGANIZATION: '势力',
    LOCATION: '地点',
    ITEM: '道具',
    PROP: '道具',
    EVENT: '事件',
    FORESHADOWING: '伏笔',
    FORESHADOW: '伏笔',
    RULE: '规则',
    WORLD_RULE: '规则',
    SETTING_RULE: '规则',
  };
  return labels[type] || '其他';
}

function relationTypeLabel(relationType?: string) {
  const value = String(relationType || '').trim();
  if (/[一-鿿]/.test(value)) {
    return value;
  }
  const labels: Record<string, string> = {
    APPEARS_IN: '出现于',
    ALLY_OF: '盟友',
    ENEMY_OF: '敌对',
    MEMBER_OF: '隶属',
    OWNS: '持有',
    LOCATED_IN: '位于',
    FORESHADOWS: '铺垫',
    RESOLVES: '回收',
    RELATED_TO: '关联',
  };
  return labels[value.toUpperCase()] || '关联';
}

function edgeLabel(edge: StoryGraphEdge) {
  const sourceId = String(edge.source ?? edge.fromNodeId ?? '');
  const targetId = String(edge.target ?? edge.toNodeId ?? '');
  return `${nodeLabels.value.get(sourceId) || '节点'} · ${relationTypeLabel(edge.relationType || edge.name)} · ${nodeLabels.value.get(targetId) || '节点'}`;
}

function navigateEvidence(chapterId?: number) {
  if (chapterId) {
    emit('evidenceNavigate', chapterId);
  }
}
</script>

<template>
  <div class="story-relationship-graph" data-test="story-relationship-graph">
    <div class="story-relationship-graph__toolbar">
      <el-select v-model="typeFilter" data-test="graph-type-filter" style="width: 140px">
        <el-option v-for="option in typeOptions" :key="option.value" :label="option.label" :value="option.value" />
      </el-select>
      <el-button data-test="graph-refresh" plain :icon="Refresh" :loading="loading" :disabled="!workId" @click="loadGraph">
        刷新图谱
      </el-button>
      <small v-if="graph?.partial" data-test="graph-partial">部分结果</small>
    </div>

    <p v-if="errorMessage" class="story-relationship-graph__error" data-test="graph-error">{{ errorMessage }}</p>

    <div class="story-relationship-graph__body">
      <div class="story-relationship-graph__chart-wrap">
        <VChart
          v-if="shouldRenderChart"
          class="story-relationship-graph__chart"
          data-test="graph-chart"
          :option="chartOption"
          autoresize
          style="height: 360px"
          @click="onChartClick"
        />
        <div v-else-if="loading" class="story-relationship-graph__empty" data-test="graph-loading">
          关系图加载中
        </div>
        <div v-else-if="hasData" class="story-relationship-graph__fallback" data-test="graph-fallback">
          当前环境不支持图表渲染，请使用下方关系列表。
        </div>
        <div v-else class="story-relationship-graph__empty" data-test="graph-empty">
          暂无关系图数据，导入并完成抽取后可浏览人物与设定关系。
        </div>
      </div>

      <aside class="story-relationship-graph__side" aria-label="关系列表">
        <h4>节点</h4>
        <button
          v-for="node in filteredNodes.slice(0, 40)"
          :key="String(node.nodeId ?? node.id)"
          type="button"
          class="story-relationship-graph__node"
          :class="{ 'is-active': selectedNode && String(selectedNode.nodeId ?? selectedNode.id) === String(node.nodeId ?? node.id) }"
          :data-test="`graph-node-${node.nodeId ?? node.id}`"
          @click="selectFromList(node)"
        >
          <span>{{ nodeLabel(node) }}</span>
          <small>{{ nodeTypeLabel(node) }}</small>
        </button>
        <h4>关系</h4>
        <div
          v-for="(edge, index) in relationList"
          :key="String(edge.edgeId ?? edge.id ?? index)"
          class="story-relationship-graph__edge"
          data-test="graph-edge-item"
        >
          <span>{{ edgeLabel(edge) }}</span>
          <el-button
            v-if="edge.evidenceChapterId"
            text
            size="small"
            :icon="Document"
            :data-test="`graph-edge-evidence-${edge.edgeId ?? edge.id ?? index}`"
            @click="navigateEvidence(edge.evidenceChapterId)"
          >
            章节证据
          </el-button>
        </div>
        <div v-if="selectedNode" class="story-relationship-graph__detail" data-test="graph-node-detail">
          <strong>{{ nodeLabel(selectedNode) }}</strong>
          <p>类型：{{ nodeTypeLabel(selectedNode) }}</p>
          <p>证据章节：{{ selectedNode.sourceChapterId || '-' }}</p>
          <p>置信度：{{ selectedNode.confidence ?? '-' }}</p>
          <el-button
            v-if="selectedNode.sourceChapterId"
            text
            size="small"
            :icon="Document"
            data-test="graph-node-evidence"
            @click="navigateEvidence(selectedNode.sourceChapterId)"
          >
            查看章节证据
          </el-button>
          <details>
            <summary>诊断信息</summary>
            <code>type={{ selectedNode.nodeType || selectedNode.category || '-' }} node={{ selectedNode.nodeId ?? selectedNode.id ?? '-' }}</code>
          </details>
        </div>
      </aside>
    </div>
  </div>
</template>

<style scoped lang="scss">
.story-relationship-graph {
  display: grid;
  gap: 0.75rem;
}

.story-relationship-graph__toolbar {
  display: flex;
  flex-wrap: wrap;
  gap: 0.5rem;
  align-items: center;
}

.story-relationship-graph__body {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(min(18rem, 100%), 1fr));
  gap: 0.75rem;
}

.story-relationship-graph__chart-wrap,
.story-relationship-graph__side {
  border: 1px solid color-mix(in srgb, var(--color-border) 85%, transparent);
  border-radius: 8px;
  padding: 0.65rem;
  min-height: 12rem;
}

.story-relationship-graph__side {
  display: grid;
  gap: 0.35rem;
  align-content: start;
  max-height: 26rem;
  overflow: auto;
}

.story-relationship-graph__node {
  display: flex;
  justify-content: space-between;
  gap: 0.5rem;
  text-align: left;
  border: 0;
  background: transparent;
  padding: 0.3rem 0.2rem;
  cursor: pointer;
  border-radius: 0.4rem;
}

.story-relationship-graph__node.is-active,
.story-relationship-graph__node:hover {
  background: color-mix(in srgb, var(--el-color-primary) 12%, transparent);
}

.story-relationship-graph__edge {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 0.35rem;
}

.story-relationship-graph__edge span {
  min-width: 0;
  overflow-wrap: anywhere;
}

.story-relationship-graph__empty,
.story-relationship-graph__fallback,
.story-relationship-graph__edge,
.story-relationship-graph__error,
.story-relationship-graph__detail p {
  margin: 0;
  font-size: 0.85rem;
  color: var(--el-text-color-secondary);
}

.story-relationship-graph__error {
  color: var(--el-color-danger);
}
</style>
