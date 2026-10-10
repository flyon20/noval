import ElementPlus from 'element-plus';
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils';
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import ProjectKnowledgeEntryList from '../ProjectKnowledgeEntryList.vue';
import { knowledgeApi } from '@/api/knowledge';

vi.mock('@/api/knowledge', () => ({
  knowledgeApi: {
    listExtractionCandidates: vi.fn(),
    getStoryGraph: vi.fn(),
  },
}));

enableAutoUnmount(afterEach);

function mountList(kind: 'characters' | 'settings' | 'foreshadowings' | 'timeline' = 'characters') {
  return mount(ProjectKnowledgeEntryList, {
    props: { projectId: 7, workId: 11, kind },
    global: { plugins: [ElementPlus] },
  });
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => {
    resolve = done;
  });
  return { promise, resolve };
}

describe('ProjectKnowledgeEntryList', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(knowledgeApi.listExtractionCandidates).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: [{
          candidateId: 31,
          projectId: 7,
          workId: 11,
          chapterId: 701,
          generationId: 18,
          entityType: 'CHARACTER',
          payloadJson: '{"name":"林舟","description":"特效工作室负责人"}',
          confidence: 0.92,
          status: 'CONFIRMED',
        }],
      },
    } as never);
    vi.mocked(knowledgeApi.getStoryGraph).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: {
          nodes: [
            { nodeId: 1, nodeType: 'CHARACTER', displayName: '顾青', sourceChapterId: 702, confidence: 0.86 },
            { nodeId: 2, nodeType: 'LOCATION', displayName: '青云城', sourceChapterId: 703, confidence: 0.8 },
          ],
          edges: [],
          partial: false,
        },
      },
    } as never);
  });

  test('combines matching candidates and graph nodes with evidence navigation', async () => {
    const wrapper = mountList();
    await flushPromises();

    expect(knowledgeApi.listExtractionCandidates).toHaveBeenCalledWith(7, {
      workId: 11,
      status: 'CONFIRMED',
      limit: 100,
    });
    expect(knowledgeApi.getStoryGraph).toHaveBeenCalledWith(7, 11, { nodeLimit: 60 });
    expect(wrapper.text()).toContain('林舟');
    expect(wrapper.text()).toContain('顾青');
    expect(wrapper.text()).not.toContain('青云城');
    expect(wrapper.text()).toContain('置信度 92%');
    expect(wrapper.text()).toContain('Generation 18');

    await wrapper.get('[data-test="knowledge-entry-evidence-31"]').trigger('click');
    expect(wrapper.emitted('evidenceNavigate')?.[0]).toEqual([701]);
  });

  test('shows a truthful empty state', async () => {
    vi.mocked(knowledgeApi.listExtractionCandidates).mockResolvedValue({
      data: { code: 200, message: 'success', data: [] },
    } as never);
    vi.mocked(knowledgeApi.getStoryGraph).mockResolvedValue({
      data: { code: 200, message: 'success', data: { nodes: [], edges: [] } },
    } as never);

    const wrapper = mountList('foreshadowings');
    await flushPromises();

    expect(wrapper.get('[data-test="knowledge-entry-empty"]').text()).toContain('暂无伏笔');
  });

  test('shows a scoped error when both sources fail', async () => {
    vi.mocked(knowledgeApi.listExtractionCandidates).mockRejectedValue(new Error('candidate unavailable'));
    vi.mocked(knowledgeApi.getStoryGraph).mockRejectedValue(new Error('graph unavailable'));

    const wrapper = mountList('timeline');
    await flushPromises();

    expect(wrapper.get('[data-test="knowledge-entry-error"]').text()).toContain('时间线资料加载失败');
    expect(wrapper.emitted('error')?.[0]).toEqual(['时间线资料加载失败']);
  });

  test('keeps available data and marks a partial result', async () => {
    vi.mocked(knowledgeApi.listExtractionCandidates).mockRejectedValue(new Error('candidate unavailable'));
    vi.mocked(knowledgeApi.getStoryGraph).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: {
          nodes: [{ nodeId: 2, nodeType: 'LOCATION', displayName: '青云城', sourceChapterId: 703 }],
          edges: [],
          partial: false,
        },
      },
    } as never);

    const wrapper = mountList('settings');
    await flushPromises();

    expect(wrapper.get('[data-test="knowledge-entry-partial"]').text()).toContain('部分资料');
    expect(wrapper.text()).toContain('青云城');
  });

  test('shows world-rule graph nodes in the settings entry', async () => {
    vi.mocked(knowledgeApi.listExtractionCandidates).mockResolvedValue({
      data: { code: 200, message: 'success', data: [] },
    } as never);
    vi.mocked(knowledgeApi.getStoryGraph).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: {
          nodes: [{ nodeId: 8, nodeType: 'WORLD_RULE', displayName: '灵力守恒', sourceChapterId: 704 }],
          edges: [],
        },
      },
    } as never);

    const wrapper = mountList('settings');
    await flushPromises();

    expect(wrapper.text()).toContain('灵力守恒');
  });

  test('ignores late data after the project scope changes', async () => {
    const oldCandidates = deferred<unknown>();
    const oldGraph = deferred<unknown>();
    vi.mocked(knowledgeApi.listExtractionCandidates).mockImplementation((projectId) => {
      if (projectId === 7) return oldCandidates.promise as never;
      return Promise.resolve({
        data: {
          code: 200,
          message: 'success',
          data: [{
            candidateId: 99,
            projectId: 9,
            workId: 12,
            entityType: 'CHARACTER',
            payloadJson: '{"name":"当前人物"}',
            status: 'CONFIRMED',
          }],
        },
      }) as never;
    });
    vi.mocked(knowledgeApi.getStoryGraph).mockImplementation((projectId) => {
      if (projectId === 7) return oldGraph.promise as never;
      return Promise.resolve({ data: { code: 200, message: 'success', data: { nodes: [], edges: [] } } }) as never;
    });
    const wrapper = mountList();
    await wrapper.setProps({ projectId: 9, workId: 12 });
    await flushPromises();
    expect(wrapper.text()).toContain('当前人物');

    oldCandidates.resolve({
      data: {
        code: 200,
        message: 'success',
        data: [{ candidateId: 31, projectId: 7, entityType: 'CHARACTER', payloadJson: '{"name":"迟到人物"}', status: 'CONFIRMED' }],
      },
    });
    oldGraph.resolve({ data: { code: 200, message: 'success', data: { nodes: [], edges: [] } } });
    await flushPromises();

    expect(wrapper.text()).toContain('当前人物');
    expect(wrapper.text()).not.toContain('迟到人物');
  });
});
