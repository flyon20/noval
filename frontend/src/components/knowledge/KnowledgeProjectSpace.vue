<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { ChatLineRound, Close, Delete, Document, FolderOpened, Plus, Refresh, Upload } from '@element-plus/icons-vue';
import { knowledgeApi } from '@/api/knowledge';
import {
  emitKnowledgeConversationSelect,
  emitKnowledgeProjectChange,
  getStoredKnowledgeProjectId,
  setStoredKnowledgeProjectId,
} from '@/composables/useKnowledgeProjectSelection';
import type { KnowledgeChatRun, KnowledgeProject, ProjectChapter, ProjectWork } from '@/types/knowledge';

const props = withDefaults(defineProps<{
  embedded?: boolean;
  closeOnSelect?: boolean;
  showMainNavAction?: boolean;
}>(), {
  embedded: false,
  closeOnSelect: false,
  showMainNavAction: false,
});

const emit = defineEmits<{
  close: [];
  showMainNav: [];
}>();

const projects = ref<KnowledgeProject[]>([]);
const activeProjectId = ref<number | null>(getStoredKnowledgeProjectId());
const projectNameDraft = ref('');
const loadingProjects = ref(false);
const loadingConversations = ref(false);
const loadingWorks = ref(false);
const loadingChapters = ref(false);
const creating = ref(false);
const creatingWork = ref(false);
const importingChapter = ref(false);
const archivingProjectId = ref<number | null>(null);
const errorMessage = ref('');
const conversationRuns = ref<KnowledgeChatRun[]>([]);
const works = ref<ProjectWork[]>([]);
const chapters = ref<ProjectChapter[]>([]);
const activeWorkId = ref<number | null>(null);
const workTitleDraft = ref('');
const chapterNoDraft = ref('');
const chapterTitleDraft = ref('');
const chapterContentDraft = ref('');

const activeProject = computed(() => (
  projects.value.find((project) => project.projectId === activeProjectId.value) ?? null
));

function mergeProjects(nextProjects: KnowledgeProject[], currentProjects: KnowledgeProject[]) {
  const nextIds = new Set(nextProjects.map((project) => project.projectId));
  return [
    ...nextProjects,
    ...currentProjects.filter((project) => !nextIds.has(project.projectId)),
  ];
}

onMounted(loadProjects);

async function loadProjects() {
  loadingProjects.value = true;
  errorMessage.value = '';
  try {
    const response = await knowledgeApi.listProjects();
    const nextProjects = response.data.data ?? [];
    projects.value = mergeProjects(nextProjects, projects.value);
    const storedProjectId = getStoredKnowledgeProjectId();
    const nextActiveId = projects.value.some((project) => project.projectId === storedProjectId)
      ? storedProjectId
      : projects.value[0]?.projectId ?? null;
    selectProject(nextActiveId, { close: false });
  } catch {
    errorMessage.value = '项目列表加载失败';
  } finally {
    loadingProjects.value = false;
  }
}

async function loadConversationRuns(projectId: number | null = activeProjectId.value) {
  loadingConversations.value = true;
  try {
    const response = await knowledgeApi.listChatRuns({
      projectId,
      limit: 20,
    });
    conversationRuns.value = response.data.data ?? [];
  } catch {
    conversationRuns.value = [];
    errorMessage.value = '会话列表加载失败';
  } finally {
    loadingConversations.value = false;
  }
}

async function loadProjectWorks(projectId: number | null = activeProjectId.value) {
  works.value = [];
  chapters.value = [];
  activeWorkId.value = null;
  if (!projectId) {
    return;
  }
  loadingWorks.value = true;
  try {
    const response = await knowledgeApi.listProjectWorks(projectId);
    works.value = response.data.data ?? [];
    activeWorkId.value = works.value[0]?.workId ?? null;
    if (activeWorkId.value) {
      await loadProjectChapters(activeWorkId.value, projectId);
    }
  } catch {
    errorMessage.value = '作品资料加载失败';
  } finally {
    loadingWorks.value = false;
  }
}

async function loadProjectChapters(workId: number | null = activeWorkId.value, projectId: number | null = activeProjectId.value) {
  chapters.value = [];
  if (!projectId || !workId) {
    return;
  }
  loadingChapters.value = true;
  try {
    const response = await knowledgeApi.listProjectChapters(projectId, workId);
    chapters.value = response.data.data ?? [];
  } catch {
    errorMessage.value = '章节资料加载失败';
  } finally {
    loadingChapters.value = false;
  }
}

async function createWork() {
  const projectId = activeProjectId.value;
  const title = workTitleDraft.value.trim();
  if (!projectId || !title || creatingWork.value) {
    return;
  }
  creatingWork.value = true;
  errorMessage.value = '';
  try {
    const response = await knowledgeApi.createProjectWork(projectId, { title });
    const work = response.data.data;
    works.value = [work, ...works.value.filter((item) => item.workId !== work.workId)];
    workTitleDraft.value = '';
    selectWork(work.workId);
  } catch {
    errorMessage.value = '作品创建失败';
  } finally {
    creatingWork.value = false;
  }
}

function selectWork(workId: number) {
  activeWorkId.value = workId;
  void loadProjectChapters(workId);
}

async function importChapter() {
  const projectId = activeProjectId.value;
  const workId = activeWorkId.value;
  const chapterNo = Number.parseInt(chapterNoDraft.value, 10);
  const content = chapterContentDraft.value.trim();
  if (!projectId || !workId || !chapterNo || !content || importingChapter.value) {
    return;
  }
  importingChapter.value = true;
  errorMessage.value = '';
  try {
    const response = await knowledgeApi.importProjectChapter(projectId, workId, {
      chapterNo,
      title: chapterTitleDraft.value.trim() || undefined,
      content,
      sourceType: 'upload',
    });
    const chapter = response.data.data;
    chapters.value = [
      ...chapters.value.filter((item) => item.chapterId !== chapter.chapterId),
      chapter,
    ].sort((left, right) => left.chapterNo - right.chapterNo || (left.version ?? 0) - (right.version ?? 0));
    chapterNoDraft.value = '';
    chapterTitleDraft.value = '';
    chapterContentDraft.value = '';
  } catch {
    errorMessage.value = '章节导入失败';
  } finally {
    importingChapter.value = false;
  }
}

async function createProject() {
  const name = projectNameDraft.value.trim();
  if (!name || creating.value) {
    return;
  }
  creating.value = true;
  errorMessage.value = '';
  try {
    const response = await knowledgeApi.createProject({ name });
    const project = response.data.data;
    projects.value = [project, ...projects.value.filter((item) => item.projectId !== project.projectId)];
    projectNameDraft.value = '';
    selectProject(project.projectId, { projectName: project.name });
  } catch {
    errorMessage.value = '项目创建失败';
  } finally {
    creating.value = false;
  }
}

async function archiveProject(project: KnowledgeProject) {
  if (archivingProjectId.value !== null) {
    return;
  }
  archivingProjectId.value = project.projectId;
  errorMessage.value = '';
  try {
    await knowledgeApi.archiveProject(project.projectId);
    projects.value = projects.value.filter((item) => item.projectId !== project.projectId);
    if (activeProjectId.value === project.projectId) {
      const nextProject = projects.value[0] ?? null;
      selectProject(nextProject?.projectId ?? null, {
        projectName: nextProject?.name,
        close: false,
      });
    }
  } catch {
    errorMessage.value = '项目删除失败';
  } finally {
    archivingProjectId.value = null;
  }
}

function selectProject(
  projectId: number | null,
  options: { projectName?: string; close?: boolean } = {},
) {
  activeProjectId.value = projectId;
  const projectName = options.projectName
    ?? projects.value.find((project) => project.projectId === projectId)?.name;
  setStoredKnowledgeProjectId(projectId);
  emitKnowledgeProjectChange({ projectId, projectName });
  void loadConversationRuns(projectId);
  void loadProjectWorks(projectId);
  if (props.closeOnSelect && options.close !== false) {
    emit('close');
  }
}

function selectConversation(run: KnowledgeChatRun) {
  const projectId = typeof run.projectId === 'number' ? run.projectId : activeProjectId.value;
  activeProjectId.value = projectId ?? null;
  setStoredKnowledgeProjectId(projectId ?? null);
  emitKnowledgeConversationSelect({
    projectId: projectId ?? null,
    projectName: projects.value.find((project) => project.projectId === projectId)?.name,
    conversationId: run.conversationId,
    runId: run.runId,
  });
  if (props.closeOnSelect) {
    emit('close');
  }
}

function runStatusLabel(status?: string) {
  switch (String(status || '').toUpperCase()) {
    case 'PENDING':
      return '排队中';
    case 'RUNNING':
      return '执行中';
    case 'ANSWERED':
      return '已回答';
    case 'FAILED':
      return '失败';
    case 'CANCELLED':
      return '已取消';
    default:
      return '未知';
  }
}

function runTimeLabel(run: KnowledgeChatRun) {
  return run.updatedAt || run.finishedAt || run.startedAt || run.queuedAt || '刚刚';
}
</script>

<template>
  <aside
    class="knowledge-project-space"
    :class="{ 'is-embedded': embedded }"
    data-test="knowledge-project-space"
  >
    <header class="knowledge-project-space__header">
      <div class="knowledge-project-space__title">
        <el-icon :size="22"><ChatLineRound /></el-icon>
        <div>
          <p>AI 问答</p>
          <h2>{{ activeProject?.name || '项目空间' }}</h2>
        </div>
      </div>
      <div class="knowledge-project-space__header-actions">
        <el-button
          v-if="showMainNavAction"
          data-test="knowledge-main-nav-toggle"
          text
          :icon="FolderOpened"
          @click="emit('showMainNav')"
        >
          主导航
        </el-button>
        <el-button
          v-if="embedded"
          circle
          text
          :icon="Close"
          aria-label="关闭项目空间"
          @click="emit('close')"
        />
      </div>
    </header>

    <section class="knowledge-project-space__create" aria-label="新建问答项目">
      <div data-test="knowledge-project-name">
        <el-input
          v-model="projectNameDraft"
          placeholder="新项目名称"
          :disabled="creating"
          @keydown.enter.prevent="createProject"
        />
      </div>
      <el-button
        data-test="knowledge-create-project"
        type="primary"
        :icon="Plus"
        :loading="creating"
        :disabled="!projectNameDraft.trim() || creating"
        aria-label="新建项目"
        @click="createProject"
      />
    </section>

    <el-alert
      v-if="errorMessage"
      class="knowledge-project-space__error"
      type="error"
      :closable="false"
      :title="errorMessage"
      show-icon
    />

    <section class="knowledge-project-space__knowledge" aria-label="作品知识库">
      <div class="knowledge-project-space__section-head">
        <h3 class="knowledge-project-space__section-title">作品资料</h3>
        <span v-if="loadingWorks">加载中</span>
      </div>
      <div class="knowledge-project-space__knowledge-tabs" aria-label="作品资料分区">
        <span>章节</span>
        <span>设定</span>
        <span>伏笔</span>
        <span>时间线</span>
      </div>
      <div class="knowledge-project-space__work-create">
        <div data-test="knowledge-work-title">
          <el-input
            v-model="workTitleDraft"
            placeholder="作品名"
            :prefix-icon="Document"
            :disabled="!activeProjectId || creatingWork"
            @keydown.enter.prevent="createWork"
          />
        </div>
        <el-button
          data-test="knowledge-create-work"
          type="primary"
          :icon="Plus"
          :loading="creatingWork"
          :disabled="!activeProjectId || !workTitleDraft.trim() || creatingWork"
          aria-label="新建作品"
          @click="createWork"
        />
      </div>
      <div class="knowledge-project-space__works">
        <button
          v-for="work in works"
          :key="work.workId"
          type="button"
          class="knowledge-project-space__work"
          :class="{ 'is-active': work.workId === activeWorkId }"
          :data-test="`knowledge-work-${work.workId}`"
          @click="selectWork(work.workId)"
        >
          <span>{{ work.title }}</span>
          <small>{{ work.genre || work.status || '作品资料' }}</small>
        </button>
        <div v-if="!works.length && !loadingWorks" class="knowledge-project-space__empty is-compact">
          还没有作品
        </div>
      </div>
      <div class="knowledge-project-space__chapter-import">
        <div data-test="knowledge-chapter-no">
          <el-input
            v-model="chapterNoDraft"
            placeholder="章号"
            inputmode="numeric"
            :disabled="!activeWorkId || importingChapter"
          />
        </div>
        <div data-test="knowledge-chapter-title">
          <el-input
            v-model="chapterTitleDraft"
            placeholder="章节标题"
            :disabled="!activeWorkId || importingChapter"
          />
        </div>
        <div data-test="knowledge-chapter-content">
          <el-input
            v-model="chapterContentDraft"
            type="textarea"
            :rows="3"
            placeholder="粘贴正文后导入"
            :disabled="!activeWorkId || importingChapter"
          />
        </div>
        <el-button
          data-test="knowledge-import-chapter"
          plain
          :icon="Upload"
          :loading="importingChapter"
          :disabled="!activeWorkId || !chapterNoDraft.trim() || !chapterContentDraft.trim() || importingChapter"
          @click="importChapter"
        >
          导入章节
        </el-button>
      </div>
      <div class="knowledge-project-space__chapters">
        <div class="knowledge-project-space__section-head">
          <h3 class="knowledge-project-space__section-title">章节</h3>
          <span v-if="loadingChapters">加载中</span>
        </div>
        <div
          v-for="chapter in chapters"
          :key="chapter.chapterId"
          class="knowledge-project-space__chapter"
        >
          <span>第{{ chapter.chapterNo }}章 {{ chapter.title || '未命名章节' }}</span>
          <small>{{ chapter.wordCount || 0 }} 字</small>
        </div>
        <div v-if="!chapters.length && !loadingChapters" class="knowledge-project-space__empty is-compact">
          还没有章节
        </div>
      </div>
    </section>

    <section class="knowledge-project-space__list" aria-label="问答项目列表">
      <div
        v-for="project in projects"
        :key="project.projectId"
        class="knowledge-project-space__row"
        :class="{ 'is-active': project.projectId === activeProjectId }"
      >
        <button
          type="button"
          class="knowledge-project-space__project"
          :data-test="`knowledge-project-${project.projectId}`"
          @click="selectProject(project.projectId, { projectName: project.name })"
        >
          <span>{{ project.name }}</span>
          <small>{{ project.updatedAt || project.createdAt || '本地会话' }}</small>
        </button>
        <el-tooltip content="删除项目" placement="top">
          <el-button
            class="knowledge-project-space__delete"
            text
            circle
            :icon="Delete"
            :loading="archivingProjectId === project.projectId"
            :data-test="`knowledge-project-delete-${project.projectId}`"
            :aria-label="`删除${project.name}`"
            @click="archiveProject(project)"
          />
        </el-tooltip>
      </div>

      <div v-if="!projects.length && !loadingProjects" class="knowledge-project-space__empty">
        暂无项目
      </div>
    </section>

    <section class="knowledge-project-space__sessions" aria-label="最近会话列表">
      <div class="knowledge-project-space__sessions-head">
        <h3 class="knowledge-project-space__section-title">最近会话</h3>
        <span v-if="loadingConversations">加载中</span>
      </div>
      <button
        v-for="run in conversationRuns"
        :key="run.runId"
        type="button"
        class="knowledge-project-space__session"
        :data-test="`knowledge-conversation-${run.conversationId}`"
        @click="selectConversation(run)"
      >
        <span>{{ run.question || '未命名会话' }}</span>
        <small>
          <i :class="`is-${String(run.status || '').toLowerCase()}`">{{ runStatusLabel(run.status) }}</i>
          {{ runTimeLabel(run) }}
        </small>
      </button>
      <div
        v-if="!conversationRuns.length && !loadingConversations"
        class="knowledge-project-space__empty"
      >
        暂无会话
      </div>
    </section>

    <el-button
      class="knowledge-project-space__refresh"
      plain
      :icon="Refresh"
      :loading="loadingProjects"
      @click="loadProjects"
    >
      刷新项目和会话
    </el-button>
  </aside>
</template>

<style scoped lang="scss">
.knowledge-project-space {
  position: fixed;
  z-index: 20;
  top: 1.35rem;
  left: 1.35rem;
  width: 330px;
  max-height: calc(100dvh - 2.7rem);
  overflow: hidden;
  display: flex;
  flex-direction: column;
  gap: 1rem;
  padding: 1.2rem;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-xl);
  box-shadow: var(--shadow-soft);
  background: color-mix(in srgb, var(--color-surface-strong) 98%, transparent);
}

.knowledge-project-space.is-embedded {
  position: static;
  width: 100%;
  height: 100%;
  min-height: 0;
  max-height: none;
  border: 0;
  border-radius: 0;
  box-shadow: none;
  background: var(--color-surface);
}

.knowledge-project-space__header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 0.75rem;
}

.knowledge-project-space__title {
  display: flex;
  min-width: 0;
  gap: 0.7rem;
  align-items: center;
}

.knowledge-project-space__title p,
.knowledge-project-space__title h2 {
  margin: 0;
}

.knowledge-project-space__title p {
  color: var(--color-text-muted);
  font-size: 0.76rem;
}

.knowledge-project-space__title h2 {
  max-width: 12.5rem;
  overflow: hidden;
  color: var(--color-text);
  font-family: var(--font-heading);
  font-size: 1rem;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.knowledge-project-space__header-actions {
  display: flex;
  flex-shrink: 0;
  align-items: center;
  gap: 0.25rem;
}

.knowledge-project-space__create {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 44px;
  gap: 0.5rem;
}

.knowledge-project-space__create :deep(.el-input__wrapper),
.knowledge-project-space__create :deep(.el-button) {
  min-height: 42px;
  border-radius: 8px;
}

.knowledge-project-space__list {
  min-height: 0;
  overflow-y: auto;
  overflow-x: hidden;
  display: grid;
  gap: 0.35rem;
  padding-right: 0.1rem;
}

.knowledge-project-space__sessions {
  min-height: 0;
  overflow-y: auto;
  display: grid;
  gap: 0.4rem;
  padding-top: 0.15rem;
  border-top: 1px solid var(--color-border);
}

.knowledge-project-space__knowledge {
  min-height: 0;
  overflow-y: auto;
  display: grid;
  gap: 0.55rem;
  padding: 0.65rem;
  border: 1px solid rgba(36, 61, 54, 0.1);
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.34);
}

.knowledge-project-space__section-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 0.5rem;
}

.knowledge-project-space__section-head span {
  color: var(--color-text-muted);
  font-size: 0.72rem;
}

.knowledge-project-space__knowledge-tabs {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 0.25rem;
}

.knowledge-project-space__knowledge-tabs span {
  min-height: 26px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: 1px solid rgba(36, 61, 54, 0.1);
  border-radius: 8px;
  color: var(--color-text-muted);
  background: rgba(255, 255, 255, 0.46);
  font-size: 0.72rem;
  font-weight: 650;
}

.knowledge-project-space__work-create {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 40px;
  gap: 0.4rem;
}

.knowledge-project-space__work-create :deep(.el-input__wrapper),
.knowledge-project-space__work-create :deep(.el-button) {
  min-height: 38px;
  border-radius: 8px;
}

.knowledge-project-space__works {
  display: grid;
  gap: 0.35rem;
}

.knowledge-project-space__work,
.knowledge-project-space__chapter {
  min-width: 0;
  display: grid;
  gap: 0.2rem;
  padding: 0.5rem 0.6rem;
  border: 1px solid rgba(36, 61, 54, 0.08);
  border-radius: 8px;
  color: var(--color-text);
  background: rgba(255, 255, 255, 0.42);
  text-align: left;
}

.knowledge-project-space__work {
  cursor: pointer;
}

.knowledge-project-space__work.is-active {
  border-color: rgba(199, 146, 92, 0.3);
  background: rgba(199, 146, 92, 0.12);
}

.knowledge-project-space__work span,
.knowledge-project-space__work small,
.knowledge-project-space__chapter span,
.knowledge-project-space__chapter small {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.knowledge-project-space__work span,
.knowledge-project-space__chapter span {
  font-size: 0.82rem;
  font-weight: 650;
}

.knowledge-project-space__work small,
.knowledge-project-space__chapter small {
  color: var(--color-text-muted);
  font-size: 0.72rem;
}

.knowledge-project-space__chapter-import {
  display: grid;
  grid-template-columns: 72px minmax(0, 1fr);
  gap: 0.4rem;
}

.knowledge-project-space__chapter-import [data-test="knowledge-chapter-content"],
.knowledge-project-space__chapter-import :deep(.el-button) {
  grid-column: 1 / -1;
}

.knowledge-project-space__chapter-import :deep(.el-input__wrapper),
.knowledge-project-space__chapter-import :deep(.el-textarea__inner),
.knowledge-project-space__chapter-import :deep(.el-button) {
  border-radius: 8px;
}

.knowledge-project-space__chapters {
  display: grid;
  gap: 0.35rem;
}

.knowledge-project-space__sessions-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 0.5rem;
}

.knowledge-project-space__sessions-head span {
  color: var(--color-text-muted);
  font-size: 0.75rem;
}

.knowledge-project-space__section-title {
  margin: 0;
  color: var(--color-text-muted);
  font-size: 0.78rem;
  font-weight: 650;
}

.knowledge-project-space__session {
  min-width: 0;
  min-height: 52px;
  display: grid;
  gap: 0.3rem;
  padding: 0.62rem 0.72rem;
  border: 1px solid rgba(36, 61, 54, 0.08);
  border-radius: 8px;
  color: var(--color-text);
  background: rgba(255, 255, 255, 0.42);
  text-align: left;
  cursor: pointer;
}

.knowledge-project-space__session:hover {
  border-color: rgba(36, 61, 54, 0.2);
  background: rgba(255, 255, 255, 0.64);
}

.knowledge-project-space__session span,
.knowledge-project-space__session small {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.knowledge-project-space__session span {
  font-weight: 620;
}

.knowledge-project-space__session small {
  display: flex;
  align-items: center;
  gap: 0.4rem;
  color: var(--color-text-muted);
  font-size: 0.72rem;
}

.knowledge-project-space__session i {
  flex: 0 0 auto;
  padding: 0.08rem 0.36rem;
  border-radius: 999px;
  color: #22543d;
  background: rgba(72, 187, 120, 0.16);
  font-style: normal;
  font-weight: 650;
}

.knowledge-project-space__session i.is-running,
.knowledge-project-space__session i.is-pending {
  color: #7a4f00;
  background: rgba(236, 180, 77, 0.2);
}

.knowledge-project-space__session i.is-failed,
.knowledge-project-space__session i.is-cancelled {
  color: #8a1f1f;
  background: rgba(229, 83, 83, 0.16);
}

.knowledge-project-space__row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 40px;
  align-items: center;
  gap: 0.35rem;
  border-radius: 8px;
  border: 1px solid transparent;
}

.knowledge-project-space__row.is-active {
  border-color: rgba(199, 146, 92, 0.24);
  background: linear-gradient(135deg, rgba(199, 146, 92, 0.12), rgba(36, 61, 54, 0.06));
}

.knowledge-project-space__project {
  min-width: 0;
  min-height: 48px;
  display: grid;
  gap: 0.2rem;
  padding: 0.55rem 0.7rem;
  border: 0;
  border-radius: 8px;
  color: var(--color-text);
  background: transparent;
  text-align: left;
  cursor: pointer;
}

.knowledge-project-space__project span,
.knowledge-project-space__project small {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.knowledge-project-space__project span {
  font-weight: 650;
}

.knowledge-project-space__project small {
  color: var(--color-text-muted);
  font-size: 0.75rem;
}

.knowledge-project-space__project:hover {
  background: rgba(255, 255, 255, 0.48);
}

.knowledge-project-space__delete {
  width: 40px;
  height: 40px;
}

.knowledge-project-space__empty {
  padding: 1.5rem 0.75rem;
  color: var(--color-text-muted);
  text-align: center;
}

.knowledge-project-space__empty.is-compact {
  padding: 0.7rem 0.4rem;
  font-size: 0.78rem;
}

.knowledge-project-space__refresh {
  justify-content: center;
  min-height: 40px;
  border-radius: 8px;
}

@media (max-width: 980px) and (min-width: 769px) {
  .knowledge-project-space {
    top: 1rem;
    left: 1rem;
    width: 280px;
    max-height: calc(100dvh - 2rem);
  }
}

@media (max-width: 768px) {
  .knowledge-project-space.is-embedded {
    gap: 0.8rem;
    padding: 0.9rem 0.65rem;
  }

  .knowledge-project-space__title h2 {
    max-width: 7.25rem;
  }

  .knowledge-project-space__create {
    grid-template-columns: minmax(0, 1fr) 42px;
    gap: 0.4rem;
  }

  .knowledge-project-space__create :deep(.el-input__wrapper),
  .knowledge-project-space__create :deep(.el-button) {
    min-height: 40px;
  }
}
</style>
