<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { knowledgeApi } from '@/api/knowledge';
import type { AiMemory, MemoryAdminQuery } from '@/types/knowledge';

defineOptions({
  name: 'AdminMemoryView',
});

const memories = ref<AiMemory[]>([]);
const candidates = ref<AiMemory[]>([]);
const loading = ref(false);
const reviewingIds = ref<Set<number>>(new Set());
const userFilter = ref('');
const projectFilter = ref('');
const statusFilter = ref('');
const scopeFilter = ref('');
const selected = ref<AiMemory | null>(null);
const drawerVisible = ref(false);

const totalCount = computed(() => memories.value.length + candidates.value.length);

onMounted(() => loadAll());

async function loadAll() {
  loading.value = true;
  try {
    const query = buildQuery();
    const [memoryResponse, candidateResponse] = await Promise.all([
      knowledgeApi.listMemories(query),
      knowledgeApi.listMemoryCandidates(query),
    ]);
    memories.value = memoryResponse.data.data ?? [];
    candidates.value = candidateResponse.data.data ?? [];
  } finally {
    loading.value = false;
  }
}

function buildQuery(): MemoryAdminQuery {
  const query: MemoryAdminQuery = { limit: 100 };
  const userId = Number(userFilter.value);
  const projectId = Number(projectFilter.value);
  if (Number.isFinite(userId) && userId > 0) query.userId = userId;
  if (Number.isFinite(projectId) && projectId > 0) query.projectId = projectId;
  if (statusFilter.value) query.status = statusFilter.value;
  if (scopeFilter.value) query.scope = scopeFilter.value;
  return query;
}

function search() {
  void loadAll();
}

function showDetail(memory: AiMemory) {
  selected.value = memory;
  drawerVisible.value = true;
}

async function approve(candidate: AiMemory) {
  reviewingIds.value = new Set(reviewingIds.value).add(candidate.id);
  try {
    await knowledgeApi.approveMemoryCandidate(candidate.id);
    await loadAll();
  } finally {
    release(candidate.id);
  }
}

async function reject(candidate: AiMemory) {
  reviewingIds.value = new Set(reviewingIds.value).add(candidate.id);
  try {
    await knowledgeApi.rejectMemoryCandidate(candidate.id);
    await loadAll();
  } finally {
    release(candidate.id);
  }
}

async function deleteMemory(memory: AiMemory) {
  reviewingIds.value = new Set(reviewingIds.value).add(memory.id);
  try {
    await knowledgeApi.deleteMemory(memory.id);
    await loadAll();
  } finally {
    release(memory.id);
  }
}

function release(id: number) {
  const next = new Set(reviewingIds.value);
  next.delete(id);
  reviewingIds.value = next;
}

function statusType(status?: string) {
  if (status === 'confirmed') return 'success';
  if (status === 'candidate') return 'warning';
  if (status === 'rejected' || status === 'deleted') return 'danger';
  return 'info';
}
</script>

<template>
  <main class="admin-memory">
    <header class="memory-header">
      <div>
        <h1>Agent 记忆</h1>
        <p>{{ totalCount }} 条记忆记录</p>
      </div>
      <div class="memory-filters">
        <el-input v-model="userFilter" data-test="memory-user-filter" clearable size="small" placeholder="用户 ID" />
        <el-input v-model="projectFilter" data-test="memory-project-filter" clearable size="small" placeholder="项目 ID" />
        <el-select v-model="statusFilter" clearable size="small" placeholder="状态">
          <el-option label="candidate" value="candidate" />
          <el-option label="confirmed" value="confirmed" />
          <el-option label="rejected" value="rejected" />
          <el-option label="deleted" value="deleted" />
        </el-select>
        <el-select v-model="scopeFilter" clearable size="small" placeholder="范围">
          <el-option label="project" value="project" />
          <el-option label="user" value="user" />
          <el-option label="thread" value="thread" />
        </el-select>
        <el-button size="small" type="primary" data-test="memory-search" @click="search">搜索</el-button>
      </div>
    </header>

    <section class="memory-section">
      <div class="section-title">
        <h2>已确认记忆</h2>
        <span>{{ memories.length }}</span>
      </div>
      <el-table v-loading="loading" :data="memories" size="small">
        <el-table-column prop="id" label="ID" width="80" />
        <el-table-column prop="userId" label="用户" width="90" />
        <el-table-column prop="projectId" label="项目" width="100" />
        <el-table-column prop="scope" label="范围" width="100" />
        <el-table-column prop="memoryType" label="类型" width="110" />
        <el-table-column prop="content" label="内容" min-width="260" show-overflow-tooltip />
        <el-table-column prop="sourceTraceId" label="来源 Trace" min-width="150" show-overflow-tooltip />
        <el-table-column label="状态" width="120">
          <template #default="{ row }">
            <el-tag :type="statusType(row.status)" size="small">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="170" fixed="right">
          <template #default="{ row }">
            <el-button size="small" @click="showDetail(row)">详情</el-button>
            <el-button size="small" data-test="delete-memory" :loading="reviewingIds.has(row.id)" @click="deleteMemory(row)">
              删除
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </section>

    <section class="memory-section">
      <div class="section-title">
        <h2>记忆候选</h2>
        <span>{{ candidates.length }}</span>
      </div>
      <el-table v-loading="loading" :data="candidates" size="small">
        <el-table-column prop="id" label="ID" width="80" />
        <el-table-column prop="userId" label="用户" width="90" />
        <el-table-column prop="projectId" label="项目" width="100" />
        <el-table-column prop="scope" label="范围" width="100" />
        <el-table-column prop="memoryType" label="类型" width="110" />
        <el-table-column prop="content" label="内容" min-width="260" show-overflow-tooltip />
        <el-table-column prop="sourceTraceId" label="来源 Trace" min-width="150" show-overflow-tooltip />
        <el-table-column label="状态" width="120">
          <template #default="{ row }">
            <el-tag :type="statusType(row.status)" size="small">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="210" fixed="right">
          <template #default="{ row }">
            <el-button size="small" data-test="approve-memory-candidate" :loading="reviewingIds.has(row.id)" @click="approve(row)">
              通过
            </el-button>
            <el-button size="small" data-test="reject-memory-candidate" :loading="reviewingIds.has(row.id)" @click="reject(row)">
              拒绝
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </section>

    <el-drawer v-model="drawerVisible" title="记忆详情" size="34rem">
      <dl v-if="selected" class="memory-detail">
        <dt>ID</dt>
        <dd>{{ selected.id }}</dd>
        <dt>用户</dt>
        <dd>{{ selected.userId || '-' }}</dd>
        <dt>项目</dt>
        <dd>{{ selected.projectId || '-' }}</dd>
        <dt>范围</dt>
        <dd>{{ selected.scope || '-' }}</dd>
        <dt>类型</dt>
        <dd>{{ selected.memoryType || '-' }}</dd>
        <dt>内容</dt>
        <dd>{{ selected.content || '-' }}</dd>
        <dt>摘要</dt>
        <dd>{{ selected.summary || '-' }}</dd>
        <dt>来源 Trace</dt>
        <dd>{{ selected.sourceTraceId || '-' }}</dd>
      </dl>
    </el-drawer>
  </main>
</template>

<style scoped>
.admin-memory {
  display: grid;
  gap: 1rem;
  padding: 1rem;
  min-width: 0;
}

.memory-header,
.memory-section {
  min-width: 0;
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
  background: var(--el-bg-color);
  padding: 1rem;
}

.memory-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 1rem;
}

.memory-header h1,
.memory-header p,
.section-title h2 {
  margin: 0;
}

.memory-header h1,
.section-title h2 {
  font-size: 1rem;
}

.memory-header p {
  margin-top: 0.25rem;
  color: var(--el-text-color-secondary);
  font-size: 0.8125rem;
}

.memory-filters {
  display: grid;
  grid-template-columns: repeat(5, minmax(7rem, auto));
  gap: 0.5rem;
}

.section-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 0.75rem;
}

.section-title span {
  color: var(--el-text-color-secondary);
  font-size: 0.8125rem;
}

.memory-detail {
  display: grid;
  grid-template-columns: 7rem minmax(0, 1fr);
  gap: 0.625rem;
}

.memory-detail dt {
  color: var(--el-text-color-secondary);
}

.memory-detail dd {
  margin: 0;
  overflow-wrap: anywhere;
}

@media (max-width: 900px) {
  .memory-header {
    display: grid;
  }

  .memory-filters {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
