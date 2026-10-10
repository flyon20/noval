<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { Document, Refresh } from '@element-plus/icons-vue';
import { knowledgeApi } from '@/api/knowledge';
import type { ProjectExtractionCandidate, StoryGraphNode } from '@/types/knowledge';

type EntryKind = 'characters' | 'settings' | 'foreshadowings' | 'timeline';

interface EntryConfig {
  label: string;
  emptyLabel: string;
  candidateTypes: Set<string>;
  nodeTypes: Set<string>;
}

interface KnowledgeEntry {
  key: string;
  id: number | string;
  label: string;
  summary?: string;
  type: string;
  status?: string;
  confidence?: number;
  chapterId?: number;
  generationId?: number;
  source: 'candidate' | 'graph';
}

const props = defineProps<{
  projectId: number | null;
  workId: number | null;
  kind: EntryKind;
}>();

const emit = defineEmits<{
  evidenceNavigate: [chapterId: number];
  error: [message: string];
}>();

const CONFIGS: Record<EntryKind, EntryConfig> = {
  characters: {
    label: '人物',
    emptyLabel: '暂无人物资料',
    candidateTypes: new Set(['CHARACTER', 'PERSON', 'CHARACTER_CARD']),
    nodeTypes: new Set(['CHARACTER', 'PERSON']),
  },
  settings: {
    label: '设定',
    emptyLabel: '暂无设定资料',
    candidateTypes: new Set(['SETTING', 'WORLD_SETTING', 'LOCATION', 'ITEM', 'PROP', 'RULE', 'WORLD_RULE', 'SETTING_RULE']),
    nodeTypes: new Set(['LOCATION', 'ITEM', 'PROP', 'RULE', 'WORLD_RULE', 'SETTING_RULE']),
  },
  foreshadowings: {
    label: '伏笔',
    emptyLabel: '暂无伏笔资料',
    candidateTypes: new Set(['FORESHADOWING', 'FORESHADOW']),
    nodeTypes: new Set(['FORESHADOWING', 'FORESHADOW']),
  },
  timeline: {
    label: '时间线',
    emptyLabel: '暂无时间线资料',
    candidateTypes: new Set(['TIMELINE', 'EVENT', 'TIMELINE_EVENT']),
    nodeTypes: new Set(['EVENT', 'TIMELINE', 'TIMELINE_EVENT']),
  },
};

const loading = ref(false);
const errorMessage = ref('');
const partial = ref(false);
const entries = ref<KnowledgeEntry[]>([]);
let loadGeneration = 0;

const config = computed(() => CONFIGS[props.kind]);

watch(
  () => [props.projectId, props.workId, props.kind] as const,
  () => {
    void loadEntries();
  },
  { immediate: true },
);

async function loadEntries() {
  const projectId = props.projectId;
  const workId = props.workId;
  const kind = props.kind;
  const generation = ++loadGeneration;
  entries.value = [];
  errorMessage.value = '';
  partial.value = false;
  if (!projectId || !workId) {
    loading.value = false;
    return;
  }

  loading.value = true;
  const [candidateResult, graphResult] = await Promise.allSettled([
    knowledgeApi.listExtractionCandidates(projectId, {
      workId,
      status: 'CONFIRMED',
      limit: 100,
    }),
    knowledgeApi.getStoryGraph(projectId, workId, { nodeLimit: 60 }),
  ]);
  if (generation !== loadGeneration
    || projectId !== props.projectId
    || workId !== props.workId
    || kind !== props.kind) {
    return;
  }

  const candidateEntries = candidateResult.status === 'fulfilled'
    ? (candidateResult.value.data.data ?? []).flatMap(candidateToEntry)
    : [];
  const graph = graphResult.status === 'fulfilled'
    ? graphResult.value.data.data
    : null;
  const graphEntries = (graph?.nodes ?? []).flatMap(nodeToEntry);
  const failedSources = Number(candidateResult.status === 'rejected') + Number(graphResult.status === 'rejected');

  entries.value = [...candidateEntries, ...graphEntries];
  partial.value = failedSources === 1 || Boolean(graph?.partial);
  if (failedSources === 2) {
    errorMessage.value = `${config.value.label}资料加载失败`;
    emit('error', errorMessage.value);
  }
  loading.value = false;
}

function candidateToEntry(candidate: ProjectExtractionCandidate): KnowledgeEntry[] {
  const type = normalizeType(candidate.entityType);
  if (!config.value.candidateTypes.has(type)) {
    return [];
  }
  const payload = parsePayload(candidate.payloadJson);
  return [{
    key: `candidate:${candidate.candidateId}`,
    id: candidate.candidateId,
    label: firstString(payload, ['displayName', 'name', 'title', 'canonicalName', 'subject'])
      || `${config.value.label}候选`,
    summary: firstString(payload, ['description', 'summary', 'content', 'detail', 'value'])
      || compactPayload(candidate.payloadJson),
    type,
    status: candidate.status,
    confidence: candidate.confidence,
    chapterId: candidate.chapterId,
    generationId: candidate.generationId,
    source: 'candidate',
  }];
}

function nodeToEntry(node: StoryGraphNode): KnowledgeEntry[] {
  const type = normalizeType(node.nodeType || node.category);
  if (!config.value.nodeTypes.has(type)) {
    return [];
  }
  const nodeId = node.nodeId ?? node.id;
  if (nodeId == null) {
    return [];
  }
  return [{
    key: `graph:${String(nodeId)}`,
    id: nodeId,
    label: String(node.displayName || node.name || `${config.value.label}节点`),
    type,
    status: node.status,
    confidence: node.confidence,
    chapterId: node.sourceChapterId,
    generationId: node.generationId,
    source: 'graph',
  }];
}

function parsePayload(raw?: string) {
  if (!raw) {
    return null;
  }
  try {
    const parsed: unknown = JSON.parse(raw);
    return typeof parsed === 'object' && parsed !== null && !Array.isArray(parsed)
      ? parsed as Record<string, unknown>
      : null;
  } catch {
    return null;
  }
}

function firstString(payload: Record<string, unknown> | null, keys: string[]) {
  if (!payload) {
    return '';
  }
  for (const key of keys) {
    const value = payload[key];
    if (typeof value === 'string' && value.trim()) {
      return value.trim();
    }
  }
  return '';
}

function compactPayload(raw?: string) {
  const value = String(raw || '').replace(/\s+/g, ' ').trim();
  return value.length > 160 ? `${value.slice(0, 160)}...` : value;
}

function normalizeType(value?: string) {
  return String(value || 'UNKNOWN').trim().toUpperCase();
}

function statusLabel(status?: string) {
  const labels: Record<string, string> = {
    PENDING: '待确认',
    CONFIRMED: '已确认',
    REJECTED: '已拒绝',
    SUPERSEDED: '已替代',
    ACTIVE: '有效',
    DISPUTED: '有冲突',
    STALE: '待更新',
  };
  return labels[normalizeType(status)] || '已记录';
}

function confidenceLabel(confidence?: number) {
  if (confidence == null || !Number.isFinite(confidence)) {
    return '';
  }
  return `置信度 ${Math.round(confidence * 100)}%`;
}
</script>

<template>
  <div class="project-knowledge-entry-list" :data-test="`knowledge-entry-list-${kind}`">
    <div class="project-knowledge-entry-list__toolbar">
      <span>{{ config.label }}资料</span>
      <el-tooltip content="刷新" placement="top">
        <el-button
          circle
          text
          :icon="Refresh"
          :loading="loading"
          :disabled="!workId"
          :aria-label="`刷新${config.label}资料`"
          data-test="knowledge-entry-refresh"
          @click="loadEntries"
        />
      </el-tooltip>
    </div>

    <p v-if="loading && !entries.length" class="project-knowledge-entry-list__state" data-test="knowledge-entry-loading">
      正在加载{{ config.label }}资料
    </p>
    <p v-if="errorMessage" class="project-knowledge-entry-list__error" data-test="knowledge-entry-error" role="alert">
      {{ errorMessage }}
    </p>
    <p v-if="partial" class="project-knowledge-entry-list__partial" data-test="knowledge-entry-partial">
      部分资料暂不可用，已保留可读取结果
    </p>

    <div v-if="entries.length" class="project-knowledge-entry-list__items">
      <article v-for="entry in entries" :key="entry.key" class="project-knowledge-entry-list__item">
        <header>
          <strong>{{ entry.label }}</strong>
          <span>{{ statusLabel(entry.status) }}</span>
        </header>
        <p v-if="entry.summary">{{ entry.summary }}</p>
        <footer>
          <small v-if="confidenceLabel(entry.confidence)">{{ confidenceLabel(entry.confidence) }}</small>
          <small v-if="entry.generationId">Generation {{ entry.generationId }}</small>
          <el-button
            v-if="entry.chapterId"
            text
            size="small"
            :icon="Document"
            :data-test="`knowledge-entry-evidence-${entry.id}`"
            @click="emit('evidenceNavigate', entry.chapterId)"
          >
            第 {{ entry.chapterId }} 章证据
          </el-button>
        </footer>
        <details>
          <summary>诊断信息</summary>
          <code>source={{ entry.source }} type={{ entry.type }} id={{ entry.id }}</code>
        </details>
      </article>
    </div>
    <p
      v-else-if="!loading && !errorMessage"
      class="project-knowledge-entry-list__state"
      data-test="knowledge-entry-empty"
    >
      {{ config.emptyLabel }}
    </p>
  </div>
</template>

<style scoped lang="scss">
.project-knowledge-entry-list {
  min-width: 0;
  display: grid;
  gap: 0.65rem;
}

.project-knowledge-entry-list__toolbar,
.project-knowledge-entry-list__item header,
.project-knowledge-entry-list__item footer {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 0.45rem;
}

.project-knowledge-entry-list__toolbar {
  min-height: 36px;
  justify-content: space-between;
  color: var(--el-text-color-regular);
  font-size: 0.82rem;
  font-weight: 650;
}

.project-knowledge-entry-list__items {
  display: grid;
  gap: 0.5rem;
}

.project-knowledge-entry-list__item {
  min-width: 0;
  display: grid;
  gap: 0.45rem;
  padding: 0.65rem;
  border: 1px solid color-mix(in srgb, var(--color-border) 85%, transparent);
  border-radius: 8px;
}

.project-knowledge-entry-list__item header {
  justify-content: space-between;
}

.project-knowledge-entry-list__item header strong,
.project-knowledge-entry-list__item p,
.project-knowledge-entry-list__item code {
  min-width: 0;
  overflow-wrap: anywhere;
}

.project-knowledge-entry-list__item header span,
.project-knowledge-entry-list__item small,
.project-knowledge-entry-list__item details,
.project-knowledge-entry-list__state,
.project-knowledge-entry-list__partial,
.project-knowledge-entry-list__error {
  font-size: 0.78rem;
}

.project-knowledge-entry-list__item header span {
  color: var(--el-color-primary);
  font-weight: 600;
}

.project-knowledge-entry-list__item p,
.project-knowledge-entry-list__state,
.project-knowledge-entry-list__partial,
.project-knowledge-entry-list__error {
  margin: 0;
}

.project-knowledge-entry-list__item p,
.project-knowledge-entry-list__item small,
.project-knowledge-entry-list__item details,
.project-knowledge-entry-list__state {
  color: var(--el-text-color-secondary);
}

.project-knowledge-entry-list__partial {
  color: var(--el-color-warning-dark-2);
}

.project-knowledge-entry-list__error {
  color: var(--el-color-danger);
}
</style>
