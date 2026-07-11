import ElementPlus from 'element-plus';
import { flushPromises, mount } from '@vue/test-utils';
import KnowledgeProjectSpace from '../KnowledgeProjectSpace.vue';
import { knowledgeApi } from '@/api/knowledge';
import {
  KNOWLEDGE_CONVERSATION_SELECT_EVENT,
  KNOWLEDGE_PROJECT_CHANGE_EVENT,
} from '@/composables/useKnowledgeProjectSelection';

vi.mock('@/api/knowledge', () => ({
  knowledgeApi: {
    listProjects: vi.fn(),
    listChatRuns: vi.fn(),
    listProjectWorks: vi.fn(),
    createProjectWork: vi.fn(),
    listProjectChapters: vi.fn(),
    importProjectChapter: vi.fn(),
    createProject: vi.fn(),
    archiveProject: vi.fn(),
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

describe('KnowledgeProjectSpace', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    window.localStorage.clear();
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
    vi.mocked(knowledgeApi.listChatRuns).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: [
          {
            runId: 'run-project-1',
            projectId: 7,
            conversationId: 'conv-project-1',
            question: '旧会话里的完整大纲',
            status: 'ANSWERED',
            answer: '旧答案',
            updatedAt: '2026-07-06T02:30:00',
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
    vi.mocked(knowledgeApi.importProjectChapter).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: { chapterId: 888, projectId: 7, workId: 71, chapterNo: 2, title: '第一次交付', wordCount: 1800 },
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

  test('loads recent conversations for the active project and broadcasts conversation selection', async () => {
    const listener = vi.fn();
    window.addEventListener(KNOWLEDGE_CONVERSATION_SELECT_EVENT, listener);

    try {
      const wrapper = mountSpace();
      await flushPromises();

      expect(knowledgeApi.listChatRuns).toHaveBeenCalledWith({ projectId: 7, limit: 20 });
      expect(wrapper.text()).toContain('最近会话');
      expect(wrapper.text()).toContain('旧会话里的完整大纲');

      await wrapper.find('[data-test="knowledge-conversation-conv-project-1"]').trigger('click');

      expect(listener).toHaveBeenCalled();
      expect(listener.mock.calls[0][0].detail).toMatchObject({
        projectId: 7,
        conversationId: 'conv-project-1',
      });
    } finally {
      window.removeEventListener(KNOWLEDGE_CONVERSATION_SELECT_EVENT, listener);
    }
  });

  test('shows project work knowledge sections and imports chapters for the selected work', async () => {
    const wrapper = mountSpace();
    await flushPromises();

    expect(knowledgeApi.listProjectWorks).toHaveBeenCalledWith(7);
    expect(knowledgeApi.listProjectChapters).toHaveBeenCalledWith(7, 71);
    expect(wrapper.text()).toContain('作品资料');
    expect(wrapper.text()).toContain('章节');
    expect(wrapper.text()).toContain('设定');
    expect(wrapper.text()).toContain('伏笔');
    expect(wrapper.text()).toContain('时间线');
    expect(wrapper.text()).toContain('诸天外包特效师');
    expect(wrapper.text()).toContain('退稿夜');

    await wrapper.find('[data-test="knowledge-work-title"] input').setValue('新书项目');
    await wrapper.find('[data-test="knowledge-create-work"]').trigger('click');
    await flushPromises();

    expect(knowledgeApi.createProjectWork).toHaveBeenCalledWith(7, { title: '新书项目' });

    await wrapper.find('[data-test="knowledge-chapter-no"] input').setValue('2');
    await wrapper.find('[data-test="knowledge-chapter-title"] input').setValue('第一次交付');
    await wrapper.find('[data-test="knowledge-chapter-content"] textarea').setValue('主角第一次用诸天外包平台完成特效。');
    await wrapper.find('[data-test="knowledge-import-chapter"]').trigger('click');
    await flushPromises();

    expect(knowledgeApi.importProjectChapter).toHaveBeenCalledWith(7, 88, {
      chapterNo: 2,
      title: '第一次交付',
      content: '主角第一次用诸天外包平台完成特效。',
      sourceType: 'upload',
    });
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
});
