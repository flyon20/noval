<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue';
import { ChatLineRound, Close, Delete, Document, FolderOpened, Plus, Refresh, Upload } from '@element-plus/icons-vue';
import { knowledgeApi } from '@/api/knowledge';
import {
  emitKnowledgeConversationSelect,
  emitKnowledgeProjectChange,
  getKnowledgeConversationsChangedDetail,
  getStoredKnowledgeProjectId,
  getStoredKnowledgeReferenceWorkIds,
  getStoredKnowledgeWorkId,
  KNOWLEDGE_CONVERSATIONS_CHANGED_EVENT,
  normalizeKnowledgeReferenceWorkIds,
  setStoredKnowledgeProjectId,
  setStoredKnowledgeReferenceWorkIds,
  setStoredKnowledgeWorkId,
} from '@/composables/useKnowledgeProjectSelection';
import ProjectIngestPanel from '@/components/knowledge/ProjectIngestPanel.vue';
import ProjectExtractionReview from '@/components/knowledge/ProjectExtractionReview.vue';
import ProjectKnowledgeEntryList from '@/components/knowledge/ProjectKnowledgeEntryList.vue';
import ProjectMemoryOverview from '@/components/knowledge/ProjectMemoryOverview.vue';
import StoryRelationshipGraph from '@/components/knowledge/StoryRelationshipGraph.vue';
import type { KnowledgeConversation, KnowledgeProject, ProjectChapter, ProjectWork } from '@/types/knowledge';

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
const archivingProjectId = ref<number | null>(null);
const errorMessage = ref('');
const conversations = ref<KnowledgeConversation[]>([]);
const works = ref<ProjectWork[]>([]);
const workLibrary = ref<ProjectWork[]>([]);
const workLibraryLoaded = ref(false);
const chapters = ref<ProjectChapter[]>([]);
const activeWorkId = ref<number | null>(null);
const referenceWorkIds = ref<number[]>(getStoredKnowledgeReferenceWorkIds(activeProjectId.value));
type KnowledgeTab = 'memory' | 'works' | 'chapters' | 'characters' | 'settings' | 'foreshadowings' | 'timeline' | 'graph' | 'ingest' | 'review';
type KnowledgeEntryKind = 'characters' | 'settings' | 'foreshadowings' | 'timeline';
const knowledgeTabs: Array<{ value: KnowledgeTab; label: string }> = [
  { value: 'memory', label: '记忆' },
  { value: 'works', label: '作品' },
  { value: 'chapters', label: '章节' },
  { value: 'characters', label: '人物' },
  { value: 'settings', label: '设定' },
  { value: 'foreshadowings', label: '伏笔' },
  { value: 'timeline', label: '时间线' },
  { value: 'graph', label: '关系' },
  { value: 'ingest', label: '导入记录' },
  { value: 'review', label: '待确认结果' },
];
const knowledgeTab = ref<KnowledgeTab>('works');
const focusedChapterId = ref<number | null>(null);
const projectSpaceElement = ref<HTMLElement | null>(null);
const workTitleDraft = ref('');
const memoryRefreshKey = ref(0);
let conversationLoadGeneration = 0;
let workLoadGeneration = 0;
let chapterLoadGeneration = 0;
let workMutationGeneration = 0;
let scrollbarTimer: number | undefined;

const activeProject = computed(() => (
  projects.value.find((project) => project.projectId === activeProjectId.value) ?? null
));
const referenceWorkOptions = computed(() => workLibrary.value.filter((work) => (
  work.workId !== activeWorkId.value
)));
const activeEntryKind = computed<KnowledgeEntryKind | null>(() => {
  if (knowledgeTab.value === 'characters'
    || knowledgeTab.value === 'settings'
    || knowledgeTab.value === 'foreshadowings'
    || knowledgeTab.value === 'timeline') {
    return knowledgeTab.value;
  }
  return null;
});
const ingestActionLabel = computed(() => {
  if (!activeProjectId.value) {
    return '请先新建或选择项目';
  }
  if (loadingWorks.value) {
    return '正在准备作品资料';
  }
  if (!activeWorkId.value) {
    return '系统将自动准备默认作品';
  }
  return '导入资料';
});

function mergeProjects(nextProjects: KnowledgeProject[], currentProjects: KnowledgeProject[]) {
  const nextIds = new Set(nextProjects.map((project) => project.projectId));
  return [
    ...nextProjects,
    ...currentProjects.filter((project) => !nextIds.has(project.projectId)),
  ];
}

onMounted(() => {
  window.addEventListener(KNOWLEDGE_CONVERSATIONS_CHANGED_EVENT, handleConversationsChanged);
  void loadProjects();
  void loadWorkLibrary();
});

onBeforeUnmount(() => {
  window.removeEventListener(KNOWLEDGE_CONVERSATIONS_CHANGED_EVENT, handleConversationsChanged);
  if (scrollbarTimer !== undefined) {
    window.clearTimeout(scrollbarTimer);
  }
});

function revealScrollbar() {
  const element = projectSpaceElement.value;
  if (!element) {
    return;
  }
  element.classList.add('is-scrolling');
  if (scrollbarTimer !== undefined) {
    window.clearTimeout(scrollbarTimer);
  }
  scrollbarTimer = window.setTimeout(() => {
    element.classList.remove('is-scrolling');
    scrollbarTimer = undefined;
  }, 850);
}

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

async function loadConversations(projectId: number | null = activeProjectId.value) {
  const generation = ++conversationLoadGeneration;
  loadingConversations.value = true;
  try {
    const response = await knowledgeApi.listConversations(projectId);
    if (generation !== conversationLoadGeneration || projectId !== activeProjectId.value) {
      return;
    }
    const nextConversations = response.data.data ?? [];
    conversations.value = projectId
      ? nextConversations
      : nextConversations.filter((conversation) => conversation.projectId == null);
  } catch {
    if (generation === conversationLoadGeneration && projectId === activeProjectId.value) {
      conversations.value = [];
      errorMessage.value = '会话列表加载失败';
    }
  } finally {
    if (generation === conversationLoadGeneration && projectId === activeProjectId.value) {
      loadingConversations.value = false;
    }
  }
}

async function loadProjectWorks(projectId: number | null = activeProjectId.value) {
  const generation = ++workLoadGeneration;
  chapterLoadGeneration++;
  works.value = [];
  chapters.value = [];
  activeWorkId.value = null;
  if (!projectId) {
    loadingWorks.value = false;
    loadingChapters.value = false;
    return;
  }
  loadingWorks.value = true;
  try {
    const response = await knowledgeApi.listProjectWorks(projectId);
    if (generation !== workLoadGeneration || projectId !== activeProjectId.value) {
      return;
    }
    works.value = response.data.data ?? [];
    const storedWorkId = getStoredKnowledgeWorkId(projectId);
    const nextWork = works.value.find((work) => work.workId === storedWorkId) ?? works.value[0] ?? null;
    updateActiveWork(nextWork?.workId ?? null, projectId);
    if (activeWorkId.value) {
      await loadProjectChapters(activeWorkId.value, projectId);
    }
  } catch {
    if (generation === workLoadGeneration && projectId === activeProjectId.value) {
      errorMessage.value = '作品资料加载失败';
    }
  } finally {
    if (generation === workLoadGeneration && projectId === activeProjectId.value) {
      loadingWorks.value = false;
    }
  }
}

async function loadProjectChapters(workId: number | null = activeWorkId.value, projectId: number | null = activeProjectId.value) {
  const generation = ++chapterLoadGeneration;
  chapters.value = [];
  if (!projectId || !workId) {
    loadingChapters.value = false;
    return;
  }
  loadingChapters.value = true;
  try {
    const response = await knowledgeApi.listProjectChapters(projectId, workId);
    if (generation !== chapterLoadGeneration
      || projectId !== activeProjectId.value
      || workId !== activeWorkId.value) {
      return;
    }
    chapters.value = response.data.data ?? [];
  } catch {
    if (generation === chapterLoadGeneration
      && projectId === activeProjectId.value
      && workId === activeWorkId.value) {
      errorMessage.value = '章节资料加载失败';
    }
  } finally {
    if (generation === chapterLoadGeneration
      && projectId === activeProjectId.value
      && workId === activeWorkId.value) {
      loadingChapters.value = false;
    }
  }
}

async function createWork() {
  const projectId = activeProjectId.value;
  const title = workTitleDraft.value.trim();
  if (creatingWork.value) {
    return;
  }
  if (!projectId) {
    errorMessage.value = '请先新建或选择项目';
    focusInput('knowledge-project-name');
    return;
  }
  if (!title) {
    focusInput('knowledge-work-title');
    return;
  }
  const generation = ++workMutationGeneration;
  creatingWork.value = true;
  errorMessage.value = '';
  try {
    const response = await knowledgeApi.createProjectWork(projectId, { title });
    if (generation !== workMutationGeneration || projectId !== activeProjectId.value) {
      return;
    }
    const work = response.data.data;
    works.value = [work, ...works.value.filter((item) => item.workId !== work.workId)];
    workLibrary.value = [work, ...workLibrary.value.filter((item) => item.workId !== work.workId)];
    workTitleDraft.value = '';
    selectWork(work.workId);
  } catch {
    if (generation === workMutationGeneration && projectId === activeProjectId.value) {
      errorMessage.value = '作品创建失败';
    }
  } finally {
    if (generation === workMutationGeneration) {
      creatingWork.value = false;
    }
  }
}

function selectWork(workId: number) {
  focusedChapterId.value = null;
  updateActiveWork(workId);
  void loadProjectChapters(workId);
}

async function loadWorkLibrary() {
  try {
    const response = await knowledgeApi.listWorkLibrary();
    workLibrary.value = response.data.data ?? [];
    workLibraryLoaded.value = true;
    setReferenceWorks(referenceWorkIds.value);
  } catch {
    workLibrary.value = [];
    workLibraryLoaded.value = true;
    setReferenceWorks([]);
  }
}

function updateActiveWork(workId: number | null, projectId: number | null = activeProjectId.value) {
  activeWorkId.value = workId;
  setStoredKnowledgeWorkId(projectId, workId);
  setReferenceWorks(referenceWorkIds.value.filter((referenceWorkId) => referenceWorkId !== workId));
  const work = works.value.find((item) => item.workId === workId);
  emitKnowledgeProjectChange({
    projectId,
    projectName: projects.value.find((project) => project.projectId === projectId)?.name,
    workId,
    workTitle: work?.title,
    referenceWorkIds: referenceWorkIds.value,
  });
}

function setReferenceWorks(workIds: unknown) {
  const normalized = normalizeKnowledgeReferenceWorkIds(workIds)
    .filter((workId) => workId !== activeWorkId.value);
  if (workLibraryLoaded.value) {
    const availableIds = new Set(referenceWorkOptions.value.map((work) => work.workId));
    referenceWorkIds.value = normalized.filter((workId) => availableIds.has(workId));
  } else {
    referenceWorkIds.value = normalized;
  }
  setStoredKnowledgeReferenceWorkIds(activeProjectId.value, referenceWorkIds.value);
}

function onReferenceWorksChange(value: unknown) {
  setReferenceWorks(value);
  emitKnowledgeProjectChange({
    projectId: activeProjectId.value,
    projectName: activeProject.value?.name,
    workId: activeWorkId.value,
    workTitle: works.value.find((work) => work.workId === activeWorkId.value)?.title,
    referenceWorkIds: referenceWorkIds.value,
  });
}

function referenceWorkLabel(work: ProjectWork) {
  const projectName = projects.value.find((project) => project.projectId === work.projectId)?.name;
  return projectName ? `${work.title} · ${projectName}` : work.title;
}

function onWorkSelect(value: string | number) {
  const workId = Number(value);
  if (Number.isInteger(workId) && workId > 0 && workId !== activeWorkId.value) {
    selectWork(workId);
  }
}

function handleConversationsChanged(event: Event) {
  const detail = getKnowledgeConversationsChangedDetail(event);
  if (detail.projectId === activeProjectId.value) {
    void loadConversations(detail.projectId);
  }
}

async function navigateToChapter(chapterId: number) {
  knowledgeTab.value = 'chapters';
  focusedChapterId.value = chapterId;
  await nextTick();
  const chapterElement = projectSpaceElement.value?.querySelector<HTMLElement>(`[data-chapter-id="${chapterId}"]`);
  chapterElement?.focus();
  chapterElement?.scrollIntoView?.({ block: 'nearest' });
}

async function createProject() {
  const name = projectNameDraft.value.trim();
  if (creating.value) {
    return;
  }
  if (!name) {
    focusInput('knowledge-project-name');
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

async function openIngest() {
  const projectId = activeProjectId.value;
  if (!projectId) {
    errorMessage.value = '请先新建或选择项目';
    focusInput('knowledge-project-name');
    return;
  }
  if (!activeWorkId.value) {
    await loadProjectWorks(projectId);
    if (projectId !== activeProjectId.value) {
      return;
    }
    if (!activeWorkId.value) {
      knowledgeTab.value = 'works';
      errorMessage.value = '作品准备失败，请刷新后重试';
      return;
    }
  }
  errorMessage.value = '';
  knowledgeTab.value = 'ingest';
}

function handleIngestReady() {
  memoryRefreshKey.value += 1;
  void loadProjectChapters(activeWorkId.value, activeProjectId.value);
}

function focusInput(testId: string) {
  void nextTick(() => {
    projectSpaceElement.value
      ?.querySelector<HTMLInputElement>(`[data-test="${testId}"] input`)
      ?.focus();
  });
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
  conversationLoadGeneration++;
  workLoadGeneration++;
  chapterLoadGeneration++;
  workMutationGeneration++;
  creatingWork.value = false;
  loadingConversations.value = false;
  loadingWorks.value = false;
  loadingChapters.value = false;
  focusedChapterId.value = null;
  activeProjectId.value = projectId;
  referenceWorkIds.value = getStoredKnowledgeReferenceWorkIds(projectId);
  const projectName = options.projectName
    ?? projects.value.find((project) => project.projectId === projectId)?.name;
  setStoredKnowledgeProjectId(projectId);
  emitKnowledgeProjectChange({ projectId, projectName, referenceWorkIds: referenceWorkIds.value });
  void loadConversations(projectId);
  void loadProjectWorks(projectId);
  if (props.closeOnSelect && options.close !== false) {
    emit('close');
  }
}

function selectConversation(conversation: KnowledgeConversation) {
  const projectId = typeof conversation.projectId === 'number' ? conversation.projectId : activeProjectId.value;
  activeProjectId.value = projectId ?? null;
  setStoredKnowledgeProjectId(projectId ?? null);
  emitKnowledgeConversationSelect({
    projectId: projectId ?? null,
    projectName: projects.value.find((project) => project.projectId === projectId)?.name,
    workId: activeWorkId.value,
    workTitle: works.value.find((work) => work.workId === activeWorkId.value)?.title,
    referenceWorkIds: referenceWorkIds.value,
    conversationId: conversation.conversationId,
    runId: conversation.lastRunId,
  });
  if (props.closeOnSelect) {
    emit('close');
  }
}

function conversationStatusLabel(status?: string) {
  switch (String(status || '').toUpperCase()) {
    case 'ACTIVE':
      return '可继续';
    case 'ARCHIVED':
      return '已归档';
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

function conversationDisplayStatus(conversation: KnowledgeConversation) {
  return conversation.lastRunStatus || conversation.status;
}

function conversationTimeLabel(conversation: KnowledgeConversation) {
  return conversation.updatedAt || conversation.createdAt || '刚刚';
}

function conversationTitle(conversation: KnowledgeConversation) {
  const title = String(conversation.title || '').trim();
  return !title || title.toLowerCase() === 'new conversation' ? '新会话' : title;
}
</script>

<template>
  <aside
    ref="projectSpaceElement"
    class="knowledge-project-space"
    :class="{ 'is-embedded': embedded }"
    data-test="knowledge-project-space"
    @scroll.passive="revealScrollbar"
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
        :disabled="creating"
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
      <div class="knowledge-project-space__scope">
        <el-select
          :model-value="activeWorkId"
          data-test="knowledge-work-selector"
          placeholder="选择作品"
          :disabled="!works.length"
          aria-label="当前作品"
          @update:model-value="onWorkSelect"
        >
          <el-option v-for="work in works" :key="work.workId" :label="work.title" :value="work.workId" />
        </el-select>
        <el-tooltip :content="ingestActionLabel" placement="top">
          <el-button
            circle
            :icon="Upload"
            data-test="knowledge-open-ingest"
            aria-label="导入资料"
            @click="openIngest"
          />
        </el-tooltip>
        <el-select
          class="knowledge-project-space__reference-selector"
          :model-value="referenceWorkIds"
          data-test="knowledge-reference-work-selector"
          multiple
          collapse-tags
          :max-collapse-tags="1"
          clearable
          placeholder="参考本人其他作品（可选）"
          :disabled="!referenceWorkOptions.length"
          aria-label="参考作品"
          @update:model-value="onReferenceWorksChange"
        >
          <el-option
            v-for="work in referenceWorkOptions"
            :key="work.workId"
            :label="referenceWorkLabel(work)"
            :value="work.workId"
          />
        </el-select>
      </div>
      <div class="knowledge-project-space__knowledge-tabs" aria-label="作品资料分区" role="tablist">
        <button
          v-for="tab in knowledgeTabs"
          :key="tab.value"
          type="button"
          role="tab"
          :data-test="`knowledge-tab-${tab.value}`"
          :class="{ 'is-active': knowledgeTab === tab.value }"
          :aria-selected="knowledgeTab === tab.value"
          @click="knowledgeTab = tab.value"
        >
          {{ tab.label }}
        </button>
      </div>
      <div v-show="knowledgeTab === 'works'" class="knowledge-project-space__works-panel">
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
            :disabled="creatingWork"
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
      </div>
      <div v-if="knowledgeTab === 'memory'" class="knowledge-project-space__panel" data-test="knowledge-memory-panel-wrap">
        <ProjectMemoryOverview
          :project-id="activeProjectId"
          :work-id="activeWorkId"
          :refresh-key="memoryRefreshKey"
        />
      </div>
      <div v-show="knowledgeTab === 'chapters'" class="knowledge-project-space__chapters">
        <div class="knowledge-project-space__section-head">
          <h3 class="knowledge-project-space__section-title">章节</h3>
          <span v-if="loadingChapters">加载中</span>
        </div>
        <div
          v-for="chapter in chapters"
          :key="chapter.chapterId"
          class="knowledge-project-space__chapter"
          :class="{ 'is-highlighted': chapter.chapterId === focusedChapterId }"
          :data-test="`knowledge-chapter-${chapter.chapterId}`"
          :data-chapter-id="chapter.chapterId"
          tabindex="-1"
        >
          <span>第{{ chapter.chapterNo }}章 {{ chapter.title || '未命名章节' }}</span>
          <small>{{ chapter.wordCount || 0 }} 字</small>
        </div>
        <div v-if="!chapters.length && !loadingChapters" class="knowledge-project-space__empty is-compact">
          还没有章节
        </div>
      </div>

      <ProjectKnowledgeEntryList
        v-if="activeEntryKind"
        :project-id="activeProjectId"
        :work-id="activeWorkId"
        :kind="activeEntryKind"
        @evidence-navigate="navigateToChapter"
      />

      <div v-show="knowledgeTab === 'ingest'" class="knowledge-project-space__panel" data-test="knowledge-ingest-panel-wrap">
        <ProjectIngestPanel
          :project-id="activeProjectId"
          :work-id="activeWorkId"
          @ready="handleIngestReady"
        />
      </div>
      <div v-show="knowledgeTab === 'review'" class="knowledge-project-space__panel" data-test="knowledge-review-panel-wrap">
        <ProjectExtractionReview
          :project-id="activeProjectId"
          :work-id="activeWorkId"
          @evidence-navigate="navigateToChapter"
        />
      </div>
      <div v-show="knowledgeTab === 'graph'" class="knowledge-project-space__panel" data-test="knowledge-graph-panel-wrap">
        <StoryRelationshipGraph
          :project-id="activeProjectId"
          :work-id="activeWorkId"
          @evidence-navigate="navigateToChapter"
        />
      </div>
    </section>

    <section class="knowledge-project-space__list" aria-label="问答项目列表">
      <div
        class="knowledge-project-space__row"
        :class="{ 'is-active': activeProjectId === null }"
      >
        <button
          type="button"
          class="knowledge-project-space__project"
          data-test="knowledge-project-unassigned"
          @click="selectProject(null)"
        >
          <span>独立问答</span>
          <small>未关联项目的会话</small>
        </button>
      </div>
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
        v-for="conversation in conversations"
        :key="conversation.conversationId"
        type="button"
        class="knowledge-project-space__session"
        :data-test="`knowledge-conversation-${conversation.conversationId}`"
        @click="selectConversation(conversation)"
      >
        <span>{{ conversationTitle(conversation) }}</span>
        <small>
          <i :class="`is-${String(conversationDisplayStatus(conversation) || '').toLowerCase()}`">
            {{ conversationStatusLabel(conversationDisplayStatus(conversation)) }}
          </i>
          {{ conversationTimeLabel(conversation) }}
        </small>
      </button>
      <div
        v-if="!conversations.length && !loadingConversations"
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
  overflow-x: hidden;
  overflow-y: auto;
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
  display: grid;
  gap: 0.35rem;
  padding-right: 0.1rem;
}

.knowledge-project-space__sessions {
  display: grid;
  gap: 0.4rem;
  padding-top: 0.15rem;
  border-top: 1px solid var(--color-border);
}

.knowledge-project-space__knowledge {
  display: grid;
  gap: 0.55rem;
  padding: 0.1rem;
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
  min-width: 0;
  min-height: 44px;
  height: 44px;
  display: flex;
  align-items: center;
  gap: 0.35rem;
  overflow-x: auto;
  padding-bottom: 0.2rem;
  scrollbar-width: thin;
}

.knowledge-project-space__knowledge-tabs button {
  flex: 0 0 auto;
  min-height: 32px;
  border: 1px solid color-mix(in srgb, var(--color-border-strong) 72%, transparent);
  border-radius: 6px;
  padding: 0.25rem 0.65rem;
  color: var(--color-text-muted);
  background: color-mix(in srgb, var(--color-surface) 88%, transparent);
  cursor: pointer;
  font-size: 0.78rem;
  font-weight: 650;
  white-space: nowrap;
}

.knowledge-project-space__knowledge-tabs button.is-active {
  color: var(--el-color-primary);
  border-color: color-mix(in srgb, var(--el-color-primary) 45%, transparent);
  background: color-mix(in srgb, var(--el-color-primary) 10%, transparent);
}

.knowledge-project-space__scope {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 40px;
  gap: 0.4rem;
}

.knowledge-project-space__scope :deep(.el-select),
.knowledge-project-space__scope :deep(.el-button) {
  width: 100%;
}

.knowledge-project-space__reference-selector {
  grid-column: 1 / -1;
}

.knowledge-project-space__works-panel {
  display: grid;
  gap: 0.4rem;
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
  border: 1px solid color-mix(in srgb, var(--color-border) 86%, transparent);
  border-radius: 8px;
  color: var(--color-text);
  background: color-mix(in srgb, var(--color-surface) 82%, transparent);
  text-align: left;
}

.knowledge-project-space__work {
  cursor: pointer;
}

.knowledge-project-space__work.is-active {
  border-color: color-mix(in srgb, var(--color-accent) 42%, var(--color-border));
  background: color-mix(in srgb, var(--color-accent) 12%, var(--color-surface));
}

.knowledge-project-space__chapter.is-highlighted {
  border-color: var(--el-color-primary);
  background: color-mix(in srgb, var(--el-color-primary) 12%, white);
}

.knowledge-project-space__chapter:focus-visible {
  outline: 2px solid var(--el-color-primary);
  outline-offset: 1px;
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
  border: 1px solid color-mix(in srgb, var(--color-border) 86%, transparent);
  border-radius: 8px;
  color: var(--color-text);
  background: color-mix(in srgb, var(--color-surface) 82%, transparent);
  text-align: left;
  cursor: pointer;
}

.knowledge-project-space__session:hover {
  border-color: color-mix(in srgb, var(--color-primary) 34%, var(--color-border));
  background: color-mix(in srgb, var(--color-primary-soft) 48%, var(--color-surface));
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
  color: var(--color-success);
  background: color-mix(in srgb, var(--color-success) 16%, transparent);
  font-style: normal;
  font-weight: 650;
}

.knowledge-project-space__session i.is-running,
.knowledge-project-space__session i.is-pending {
  color: var(--color-accent-strong);
  background: color-mix(in srgb, var(--color-accent) 18%, transparent);
}

.knowledge-project-space__session i.is-failed,
.knowledge-project-space__session i.is-cancelled {
  color: var(--color-danger);
  background: color-mix(in srgb, var(--color-danger) 16%, transparent);
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
  border-color: color-mix(in srgb, var(--color-accent-strong) 28%, var(--color-border));
  background: var(--gradient-soft);
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
  background: color-mix(in srgb, var(--color-primary-soft) 42%, var(--color-surface));
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

.knowledge-project-space__panel {
  min-width: 0;
}
</style>
