import ElementPlus from 'element-plus';
import { flushPromises, mount } from '@vue/test-utils';
import KnowledgeProjectSpace from '../KnowledgeProjectSpace.vue';
import ProjectIngestPanel from '../ProjectIngestPanel.vue';
import { knowledgeApi } from '@/api/knowledge';
import {
  KNOWLEDGE_ACTIVE_WORK_STORAGE_PREFIX,
  KNOWLEDGE_CONVERSATIONS_CHANGED_EVENT,
  KNOWLEDGE_CONVERSATION_SELECT_EVENT,
  KNOWLEDGE_PROJECT_CHANGE_EVENT,
} from '@/composables/useKnowledgeProjectSelection';

vi.mock('@/api/knowledge', () => ({
  knowledgeApi: {
    listProjects: vi.fn(),
    listConversations: vi.fn(),
    listChatRuns: vi.fn(),
    listProjectWorks: vi.fn(),
    listWorkLibrary: vi.fn(),
    createProjectWork: vi.fn(),
    listProjectChapters: vi.fn(),
    createProject: vi.fn(),
    archiveProject: vi.fn(),
    listProjectIngestJobs: vi.fn(),
    submitProjectIngestJob: vi.fn(),
    retryProjectIngestJob: vi.fn(),
    listExtractionCandidates: vi.fn(),
    reviewExtractionCandidate: vi.fn(),
    getStoryGraph: vi.fn(),
    getProjectMemoryOverview: vi.fn(),
  },
}));

function mountSpace() {
  return mount(KnowledgeProjectSpace, {
    props: {
      showMainNavAction: true,
    },
    global: {
      plugins: [ElementPlus],
    },
  });
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => {
    resolve = done;
  });
  return { promise, resolve };
}

describe('KnowledgeProjectSpace', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    window.localStorage.clear();
    vi.mocked(knowledgeApi.listProjectIngestJobs).mockResolvedValue({ data: { code: 200, message: 'success', data: [] } } as never);
    vi.mocked(knowledgeApi.listExtractionCandidates).mockResolvedValue({ data: { code: 200, message: 'success', data: [] } } as never);
    vi.mocked(knowledgeApi.getStoryGraph).mockResolvedValue({ data: { code: 200, message: 'success', data: { nodes: [], edges: [] } } } as never);
    vi.mocked(knowledgeApi.getProjectMemoryOverview).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: {
          projectId: 7,
          workId: 71,
          activeChapterCount: 2,
          chapterFrom: 1,
          chapterTo: 2,
          indexedDocumentCount: 1,
          characterStateCount: 0,
          worldRuleCount: 0,
          foreshadowingCount: 0,
          foreshadowingStatusCounts: {},
          timelineEventCount: 0,
          storyNodeCount: 0,
          storyEdgeCount: 0,
          pendingExtractionCount: 0,
          longFormFactCount: 0,
          pendingLongFormFactCount: 0,
          longFormFactStatusCounts: {},
          summaryNodeCount: 0,
          summaryCoveredChapterCount: 0,
          summaryCoverageStatus: 'NOT_BUILT',
          summaryNodeTypeCounts: {},
          recognizedRecordsOnly: true,
        },
      },
    } as never);
    vi.mocked(knowledgeApi.listProjects).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: [
          { projectId: 7, name: '旧项目' },
          { projectId: 9, name: '新题材' },
        ],
      },
    } as never);
    vi.mocked(knowledgeApi.listConversations).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: [
          {
            conversationId: 'conv-project-1',
            projectId: 7,
            title: 'Project outline',
            status: 'ACTIVE',
            lastRunId: 'run-project-2',
            updatedAt: '2026-07-06T02:30:00',
          },
          {
            conversationId: 'conv-project-2',
            projectId: 7,
            title: 'Empty draft',
            status: 'ACTIVE',
            createdAt: '2026-07-06T03:00:00',
            messages: [],
          },
        ],
      },
    } as never);
    vi.mocked(knowledgeApi.listChatRuns).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: [
          {
            runId: 'run-project-2',
            projectId: 7,
            conversationId: 'conv-project-1',
            question: 'Latest outline question',
            status: 'ANSWERED',
            answer: 'Latest answer',
            updatedAt: '2026-07-06T02:30:00',
          },
          {
            runId: 'run-project-1',
            projectId: 7,
            conversationId: 'conv-project-1',
            question: 'Earlier outline question',
            status: 'ANSWERED',
            answer: 'Earlier answer',
            updatedAt: '2026-07-05T02:30:00',
          },
        ],
      },
    } as never);
    vi.mocked(knowledgeApi.listProjectWorks).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: [
          { workId: 71, projectId: 7, title: '诸天外包特效师', genre: '都市脑洞', status: 'ACTIVE' },
        ],
      },
    } as never);
    vi.mocked(knowledgeApi.listProjectChapters).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: [
          { chapterId: 701, projectId: 7, workId: 71, chapterNo: 1, title: '退稿夜', wordCount: 1200 },
        ],
      },
    } as never);
    vi.mocked(knowledgeApi.createProjectWork).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: { workId: 88, projectId: 7, title: '新书项目', genre: '都市脑洞', status: 'ACTIVE' },
      },
    } as never);
    vi.mocked(knowledgeApi.createProject).mockResolvedValue({
      data: { code: 200, message: 'success', data: { projectId: 99, name: '都市脑洞' } },
    } as never);
    vi.mocked(knowledgeApi.archiveProject).mockResolvedValue({
      data: { code: 200, message: 'success', data: undefined },
    } as never);
  });

  test('creates a project and broadcasts the active project change', async () => {
    const listener = vi.fn();
    window.addEventListener(KNOWLEDGE_PROJECT_CHANGE_EVENT, listener);

    try {
      const wrapper = mountSpace();
      await flushPromises();

      await wrapper.find('[data-test="knowledge-project-name"] input').setValue('都市脑洞');
      await wrapper.find('[data-test="knowledge-create-project"]').trigger('click');
      await flushPromises();

      expect(knowledgeApi.createProject).toHaveBeenCalledWith({ name: '都市脑洞' });
      expect(listener).toHaveBeenCalled();
      expect(window.localStorage.getItem('noval:knowledge-chat:active-project:v1')).toBe('99');
      expect(wrapper.text()).toContain('都市脑洞');
    } finally {
      window.removeEventListener(KNOWLEDGE_PROJECT_CHANGE_EVENT, listener);
    }
  });

  test('loads one recent session per conversation and broadcasts conversation selection', async () => {
    const listener = vi.fn();
    window.addEventListener(KNOWLEDGE_CONVERSATION_SELECT_EVENT, listener);

    try {
      const wrapper = mountSpace();
      await flushPromises();

      expect(knowledgeApi.listConversations).toHaveBeenCalledWith(7);
      expect(knowledgeApi.listChatRuns).not.toHaveBeenCalled();
      expect(wrapper.findAll('[data-test^="knowledge-conversation-"]')).toHaveLength(2);
      expect(wrapper.text()).toContain('最近会话');
      expect(wrapper.text()).toContain('Project outline');
      expect(wrapper.text()).toContain('Empty draft');

      await wrapper.find('[data-test="knowledge-conversation-conv-project-1"]').trigger('click');

      expect(listener).toHaveBeenCalled();
      expect(listener.mock.calls[0][0].detail).toMatchObject({
        projectId: 7,
        workId: 71,
        conversationId: 'conv-project-1',
        runId: 'run-project-2',
      });
    } finally {
      window.removeEventListener(KNOWLEDGE_CONVERSATION_SELECT_EVENT, listener);
    }
  });

  test('does not duplicate a conversation when it has multiple runs', async () => {
    vi.mocked(knowledgeApi.listConversations).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: [
          {
            conversationId: 'conv-project-1',
            projectId: 7,
            title: 'Project outline',
            status: 'ACTIVE',
            lastRunId: 'run-project-2',
          },
        ],
      },
    } as never);

    const wrapper = mountSpace();
    await flushPromises();

    expect(wrapper.findAll('[data-test="knowledge-conversation-conv-project-1"]')).toHaveLength(1);
  });

  test('shows the latest run status instead of the conversation lifecycle status', async () => {
    vi.mocked(knowledgeApi.listConversations).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: [{
          conversationId: 'conv-answered',
          projectId: 7,
          title: '已完成会话',
          status: 'ACTIVE',
          lastRunId: 'run-answered',
          lastRunStatus: 'ANSWERED',
        }],
      },
    } as never);
    vi.mocked(knowledgeApi.listWorkLibrary).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: [
          { workId: 71, projectId: 7, title: '当前作品', status: 'ACTIVE' },
          { workId: 81, projectId: 8, title: '本人旧作', status: 'ACTIVE' },
        ],
      },
    } as never);

    const wrapper = mountSpace();
    await flushPromises();

    const conversation = wrapper.get('[data-test="knowledge-conversation-conv-answered"]');
    expect(conversation.text()).toContain('已回答');
    expect(conversation.text()).not.toContain('进行中');
  });

  test('shows the initial empty conversation created with a new project', async () => {
    vi.mocked(knowledgeApi.listConversations).mockImplementation((projectId) => Promise.resolve({
      data: {
        code: 200,
        message: 'success',
        data: projectId === 99
          ? [{
              conversationId: 'conv-initial',
              projectId: 99,
              title: 'New conversation',
              status: 'ACTIVE',
              messages: [],
            }]
          : [],
      },
    }) as never);

    const wrapper = mountSpace();
    await flushPromises();

    await wrapper.find('[data-test="knowledge-project-name"] input').setValue('New project');
    await wrapper.find('[data-test="knowledge-create-project"]').trigger('click');
    await flushPromises();

    expect(knowledgeApi.listConversations).toHaveBeenCalledWith(99);
    expect(wrapper.find('[data-test="knowledge-conversation-conv-initial"]').exists()).toBe(true);
    expect(wrapper.text()).toContain('新会话');
  });

  test('prepares a default work for a new project and opens import without another prerequisite', async () => {
    vi.mocked(knowledgeApi.listProjectWorks).mockImplementation((projectId) => Promise.resolve({
      data: {
        code: 200,
        message: 'success',
        data: [{
          workId: projectId === 99 ? 990 : 71,
          projectId,
          title: projectId === 99 ? '都市脑洞' : '诸天外包特效师',
          status: 'ACTIVE',
        }],
      },
    }) as never);
    const wrapper = mountSpace();
    await flushPromises();

    await wrapper.find('[data-test="knowledge-project-name"] input').setValue('都市脑洞');
    await wrapper.find('[data-test="knowledge-create-project"]').trigger('click');
    await flushPromises();
    await wrapper.find('[data-test="knowledge-open-ingest"]').trigger('click');
    await flushPromises();

    expect(knowledgeApi.listProjectWorks).toHaveBeenCalledWith(99);
    expect(wrapper.get('[data-test="knowledge-tab-ingest"]').attributes('aria-selected')).toBe('true');
    expect(wrapper.find('[data-test="project-ingest-panel"]').exists()).toBe(true);
    expect(wrapper.text()).not.toContain('请先新建或选择作品');
    expect(window.localStorage.getItem(`${KNOWLEDGE_ACTIVE_WORK_STORAGE_PREFIX}99`)).toBe('990');
  });

  test('shows all ten scoped knowledge entries and creates works', async () => {
    const wrapper = mountSpace();
    await flushPromises();

    expect(knowledgeApi.listProjectWorks).toHaveBeenCalledWith(7);
    expect(knowledgeApi.listProjectChapters).toHaveBeenCalledWith(7, 71);
    expect(wrapper.text()).toContain('作品资料');
    const tabs = [
      ['memory', '记忆'],
      ['works', '作品'],
      ['chapters', '章节'],
      ['characters', '人物'],
      ['settings', '设定'],
      ['foreshadowings', '伏笔'],
      ['timeline', '时间线'],
      ['graph', '关系'],
      ['ingest', '导入记录'],
      ['review', '待确认结果'],
    ];
    for (const [key, label] of tabs) {
      expect(wrapper.get(`[data-test="knowledge-tab-${key}"]`).text()).toBe(label);
    }
    expect(wrapper.text()).toContain('诸天外包特效师');

    await wrapper.find('[data-test="knowledge-work-title"] input').setValue('新书项目');
    await wrapper.find('[data-test="knowledge-create-work"]').trigger('click');
    await flushPromises();

    expect(knowledgeApi.createProjectWork).toHaveBeenCalledWith(7, { title: '新书项目' });
    expect(wrapper.find('[data-test="knowledge-work-88"]').exists()).toBe(true);
  });

  test('offers other owned works as explicit references and broadcasts only the selection', async () => {
    const listener = vi.fn();
    window.addEventListener(KNOWLEDGE_PROJECT_CHANGE_EVENT, listener);
    try {
      const wrapper = mountSpace();
      await flushPromises();

      expect(knowledgeApi.listWorkLibrary).toHaveBeenCalledOnce();
      const selector = wrapper.get('[data-test="knowledge-reference-work-selector"]');
      selector.findComponent({ name: 'ElSelect' }).vm.$emit('update:modelValue', [81]);
      await flushPromises();

      expect(window.localStorage.getItem('noval:knowledge-chat:reference-works:v1:7')).toBe('[81]');
      expect(listener.mock.calls.at(-1)?.[0].detail).toMatchObject({
        projectId: 7,
        workId: 71,
        referenceWorkIds: [81],
      });
    } finally {
      window.removeEventListener(KNOWLEDGE_PROJECT_CHANGE_EVENT, listener);
    }
  });

  test('refreshes visible chapters when an ingest job becomes ready', async () => {
    const wrapper = mountSpace();
    await flushPromises();
    vi.mocked(knowledgeApi.listProjectChapters).mockClear();

    wrapper.findComponent(ProjectIngestPanel).vm.$emit('ready', {
      ingestJobId: 100,
      projectId: 7,
      workId: 71,
      chapterNo: 2,
      status: 'READY',
    });
    await flushPromises();

    expect(knowledgeApi.listProjectChapters).toHaveBeenCalledWith(7, 71);
  });

  test('shows unassigned recent conversations when there are no projects', async () => {
    vi.mocked(knowledgeApi.listProjects).mockResolvedValue({
      data: { code: 200, message: 'success', data: [] },
    } as never);
    vi.mocked(knowledgeApi.listConversations).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: [
          { conversationId: 'conv-unassigned', title: '历史问答', status: 'ACTIVE' },
          { conversationId: 'conv-other-project', projectId: 9, title: '其他项目', status: 'ACTIVE' },
        ],
      },
    } as never);

    const listener = vi.fn();
    window.addEventListener(KNOWLEDGE_CONVERSATION_SELECT_EVENT, listener);

    try {
      const wrapper = mountSpace();
      await flushPromises();

      expect(knowledgeApi.listConversations).toHaveBeenCalledWith(null);
      expect(wrapper.find('[data-test="knowledge-conversation-conv-unassigned"]').exists()).toBe(true);
      expect(wrapper.find('[data-test="knowledge-conversation-conv-other-project"]').exists()).toBe(false);

      await wrapper.find('[data-test="knowledge-conversation-conv-unassigned"]').trigger('click');
      expect(listener.mock.calls[0][0].detail).toMatchObject({
        projectId: null,
        conversationId: 'conv-unassigned',
      });
    } finally {
      window.removeEventListener(KNOWLEDGE_CONVERSATION_SELECT_EVENT, listener);
    }
  });

  test('keeps unassigned conversations reachable when projects exist', async () => {
    vi.mocked(knowledgeApi.listConversations).mockImplementation((projectId) => Promise.resolve({
      data: {
        code: 200,
        message: 'success',
        data: projectId == null
          ? [
              { conversationId: 'conv-unassigned', title: '独立历史问答', status: 'ACTIVE' },
              { conversationId: 'conv-other-project', projectId: 9, title: '其他项目', status: 'ACTIVE' },
            ]
          : [{ conversationId: 'conv-project', projectId, title: '项目问答', status: 'ACTIVE' }],
      },
    }) as never);

    const wrapper = mountSpace();
    await flushPromises();

    expect(wrapper.find('[data-test="knowledge-conversation-conv-project"]').exists()).toBe(true);

    await wrapper.find('[data-test="knowledge-project-unassigned"]').trigger('click');
    await flushPromises();

    expect(knowledgeApi.listConversations).toHaveBeenCalledWith(null);
    expect(wrapper.find('[data-test="knowledge-conversation-conv-unassigned"]').exists()).toBe(true);
    expect(wrapper.find('[data-test="knowledge-conversation-conv-other-project"]').exists()).toBe(false);
  });

  test('refreshes the active conversation list after a conversation change', async () => {
    const wrapper = mountSpace();
    await flushPromises();
    vi.mocked(knowledgeApi.listConversations).mockClear();
    vi.mocked(knowledgeApi.listConversations).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: [{ conversationId: 'conv-new', projectId: 7, title: '刚创建的会话', status: 'ACTIVE' }],
      },
    } as never);

    window.dispatchEvent(new CustomEvent(KNOWLEDGE_CONVERSATIONS_CHANGED_EVENT, {
      detail: { projectId: 7 },
    }));
    await flushPromises();

    expect(knowledgeApi.listConversations).toHaveBeenCalledWith(7);
    expect(wrapper.find('[data-test="knowledge-conversation-conv-new"]').exists()).toBe(true);
  });

  test('keeps add and import actions actionable while guiding missing prerequisites', async () => {
    vi.mocked(knowledgeApi.listProjects).mockResolvedValue({
      data: { code: 200, message: 'success', data: [] },
    } as never);
    vi.mocked(knowledgeApi.listConversations).mockResolvedValue({
      data: { code: 200, message: 'success', data: [] },
    } as never);

    const wrapper = mountSpace();
    await flushPromises();

    expect(wrapper.find('[data-test="knowledge-create-project"]').attributes('disabled')).toBeUndefined();
    expect(wrapper.find('[data-test="knowledge-create-work"]').attributes('disabled')).toBeUndefined();
    expect(wrapper.find('[data-test="knowledge-open-ingest"]').attributes('disabled')).toBeUndefined();

    await wrapper.find('[data-test="knowledge-open-ingest"]').trigger('click');
    await flushPromises();

    expect(wrapper.text()).toContain('请先新建或选择项目');
    expect(knowledgeApi.createProject).not.toHaveBeenCalled();
  });

  test('shows the project-space scrollbar only while the user is scrolling', async () => {
    vi.useFakeTimers();
    const wrapper = mountSpace();

    try {
      await Promise.resolve();
      const space = wrapper.get('[data-test="knowledge-project-space"]');
      await space.trigger('scroll');

      expect(space.classes()).toContain('is-scrolling');
      vi.advanceTimersByTime(850);
      expect(space.classes()).not.toContain('is-scrolling');
    } finally {
      wrapper.unmount();
      vi.useRealTimers();
    }
  });

  test('allows creating a project while the project list is still loading', async () => {
    vi.mocked(knowledgeApi.listProjects).mockReturnValue(new Promise(() => undefined) as never);

    const wrapper = mount(KnowledgeProjectSpace, {
      props: {
        embedded: true,
        closeOnSelect: true,
      },
      global: {
        plugins: [ElementPlus],
      },
    });

    await wrapper.find('[data-test="knowledge-project-name"] input').setValue('mobile project');
    await wrapper.find('[data-test="knowledge-create-project"]').trigger('click');
    await flushPromises();

    expect(knowledgeApi.createProject).toHaveBeenCalledWith({ name: 'mobile project' });
  });

  test('keeps a newly created project if the initial project list resolves later', async () => {
    let resolveList!: (value: unknown) => void;
    vi.mocked(knowledgeApi.listProjects).mockReturnValue(new Promise((resolve) => {
      resolveList = resolve;
    }) as never);

    const wrapper = mount(KnowledgeProjectSpace, {
      props: {
        embedded: true,
        closeOnSelect: true,
      },
      global: {
        plugins: [ElementPlus],
      },
    });

    await wrapper.find('[data-test="knowledge-project-name"] input').setValue('late project');
    await wrapper.find('[data-test="knowledge-create-project"]').trigger('click');
    await flushPromises();

    resolveList({
      data: {
        code: 200,
        message: 'success',
        data: [],
      },
    });
    await flushPromises();

    expect(wrapper.text()).toContain('都市脑洞');
  });

  test('ignores a late work response after switching projects', async () => {
    const oldWorks = deferred<unknown>();
    vi.mocked(knowledgeApi.listProjectWorks).mockImplementation((projectId) => {
      if (projectId === 7) {
        return oldWorks.promise as never;
      }
      return Promise.resolve({
        data: {
          code: 200,
          message: 'success',
          data: [{ workId: 91, projectId: 9, title: '项目九作品', status: 'ACTIVE' }],
        },
      }) as never;
    });
    vi.mocked(knowledgeApi.listProjectChapters).mockResolvedValue({
      data: { code: 200, message: 'success', data: [] },
    } as never);

    const wrapper = mountSpace();
    await flushPromises();
    await wrapper.find('[data-test="knowledge-project-9"]').trigger('click');
    await flushPromises();
    expect(wrapper.find('[data-test="knowledge-work-91"]').exists()).toBe(true);

    oldWorks.resolve({
      data: {
        code: 200,
        message: 'success',
        data: [{ workId: 71, projectId: 7, title: '迟到的项目七作品', status: 'ACTIVE' }],
      },
    });
    await flushPromises();

    expect(wrapper.find('[data-test="knowledge-work-91"]').exists()).toBe(true);
    expect(wrapper.find('[data-test="knowledge-work-71"]').exists()).toBe(false);
  });

  test('ignores a late chapter response after switching works', async () => {
    const oldChapters = deferred<unknown>();
    vi.mocked(knowledgeApi.listProjectWorks).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: [
          { workId: 71, projectId: 7, title: '作品一', status: 'ACTIVE' },
          { workId: 72, projectId: 7, title: '作品二', status: 'ACTIVE' },
        ],
      },
    } as never);
    vi.mocked(knowledgeApi.listProjectChapters).mockImplementation((_projectId, workId) => {
      if (workId === 71) {
        return oldChapters.promise as never;
      }
      return Promise.resolve({
        data: {
          code: 200,
          message: 'success',
          data: [{ chapterId: 720, projectId: 7, workId: 72, chapterNo: 2, title: '当前章节' }],
        },
      }) as never;
    });

    const wrapper = mountSpace();
    await flushPromises();
    await wrapper.find('[data-test="knowledge-work-72"]').trigger('click');
    await flushPromises();
    expect(wrapper.find('[data-test="knowledge-chapter-720"]').exists()).toBe(true);

    oldChapters.resolve({
      data: {
        code: 200,
        message: 'success',
        data: [{ chapterId: 710, projectId: 7, workId: 71, chapterNo: 1, title: '迟到章节' }],
      },
    });
    await flushPromises();

    expect(wrapper.find('[data-test="knowledge-chapter-720"]').exists()).toBe(true);
    expect(wrapper.find('[data-test="knowledge-chapter-710"]').exists()).toBe(false);
  });

  test('archives a project and selects the next available project', async () => {
    window.localStorage.setItem('noval:knowledge-chat:active-project:v1', '7');
    const wrapper = mountSpace();
    await flushPromises();

    await wrapper.find('[data-test="knowledge-project-delete-7"]').trigger('click');
    await flushPromises();

    expect(knowledgeApi.archiveProject).toHaveBeenCalledWith(7);
    expect(wrapper.text()).not.toContain('旧项目');
    expect(window.localStorage.getItem('noval:knowledge-chat:active-project:v1')).toBe('9');
  });

  test('switches knowledge tabs without clearing current project context', async () => {
    const wrapper = mountSpace();
    await flushPromises();
    expect(knowledgeApi.getProjectMemoryOverview).not.toHaveBeenCalled();
    await wrapper.get('[data-test="knowledge-tab-memory"]').trigger('click');
    await flushPromises();
    expect(wrapper.find('[data-test="project-memory-overview"]').exists()).toBe(true);
    expect(knowledgeApi.getProjectMemoryOverview).toHaveBeenCalledWith(7, 71);
    await wrapper.get('[data-test="knowledge-tab-characters"]').trigger('click');
    await flushPromises();
    expect(wrapper.find('[data-test="knowledge-entry-list-characters"]').exists()).toBe(true);
    await wrapper.get('[data-test="knowledge-tab-ingest"]').trigger('click');
    await flushPromises();
    expect(wrapper.get('[data-test="knowledge-tab-ingest"]').attributes('aria-selected')).toBe('true');
    expect(wrapper.find('[data-test="project-ingest-panel"]').exists()).toBe(true);
    await wrapper.get('[data-test="knowledge-tab-review"]').trigger('click');
    await flushPromises();
    expect(wrapper.get('[data-test="knowledge-tab-review"]').attributes('aria-selected')).toBe('true');
    expect(wrapper.find('[data-test="project-extraction-review"]').exists()).toBe(true);
    await wrapper.get('[data-test="knowledge-tab-graph"]').trigger('click');
    await flushPromises();
    expect(wrapper.get('[data-test="knowledge-tab-graph"]').attributes('aria-selected')).toBe('true');
    expect(wrapper.find('[data-test="story-relationship-graph"]').exists()).toBe(true);
    expect(wrapper.find('[data-test="project-ingest-panel"]').exists()).toBe(true);
    expect(wrapper.find('[data-test="project-extraction-review"]').exists()).toBe(true);
    expect(wrapper.get('[data-test="knowledge-project-space"]').exists()).toBe(true);
  });

  test('returns to and highlights a chapter from graph evidence', async () => {
    vi.mocked(knowledgeApi.getStoryGraph).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: {
          nodes: [{ nodeId: 1, nodeType: 'CHARACTER', displayName: '林舟', sourceChapterId: 701 }],
          edges: [],
        },
      },
    } as never);
    const wrapper = mountSpace();
    await flushPromises();
    await wrapper.get('[data-test="knowledge-tab-graph"]').trigger('click');
    await flushPromises();
    await wrapper.get('[data-test="graph-node-1"]').trigger('click');
    await wrapper.get('[data-test="graph-node-evidence"]').trigger('click');
    await flushPromises();

    expect(wrapper.get('[data-test="knowledge-tab-chapters"]').attributes('aria-selected')).toBe('true');
    expect(wrapper.get('[data-test="knowledge-chapter-701"]').classes()).toContain('is-highlighted');
  });

});
