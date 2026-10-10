import ElementPlus from 'element-plus';
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils';
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import StoryRelationshipGraph from '../StoryRelationshipGraph.vue';
import { knowledgeApi } from '@/api/knowledge';

vi.mock('@/api/knowledge', () => ({
  knowledgeApi: {
    getStoryGraph: vi.fn(),
  },
}));

vi.mock('vue-echarts', () => ({
  default: {
    name: 'VChart',
    props: ['option'],
    template: '<div data-test="graph-chart-mock" />',
  },
}));

enableAutoUnmount(afterEach);

function mountGraph(projectId = 7, workId = 11) {
  return mount(StoryRelationshipGraph, {
    props: { projectId, workId },
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

describe('StoryRelationshipGraph', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(knowledgeApi.getStoryGraph).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: {
          nodes: [
            { nodeId: 1, id: 1, nodeType: 'CHARACTER', displayName: '林舟', sourceChapterId: 701, confidence: 0.9 },
            { nodeId: 2, id: 2, nodeType: 'LOCATION', displayName: '青云城', sourceChapterId: 702, confidence: 0.8 },
            { nodeId: 3, id: 3, nodeType: 'ORGANIZATION', displayName: '天工盟', sourceChapterId: 703, confidence: 0.88 },
          ],
          edges: [
            {
              edgeId: 9,
              source: '1',
              target: '2',
              fromNodeId: 1,
              toNodeId: 2,
              relationType: 'APPEARS_IN',
              evidenceChapterId: 704,
            },
          ],
          gaps: [],
          partial: false,
        },
      },
    } as never);
  });

  test('renders accessible lists, focuses nodes, and navigates evidence', async () => {
    const wrapper = mountGraph();
    await flushPromises();

    expect(knowledgeApi.getStoryGraph).toHaveBeenCalledWith(7, 11, { nodeLimit: 60 });
    expect(wrapper.get('[data-test="graph-node-1"]').text()).toContain('林舟');
    expect(wrapper.get('[data-test="graph-edge-item"]').text()).toContain('出现于');
    await wrapper.get('[data-test="graph-node-1"]').trigger('click');
    expect(wrapper.get('[data-test="graph-node-detail"]').text()).toContain('林舟');
    expect(wrapper.get('[data-test="graph-node-detail"]').text()).toContain('证据章节');

    await wrapper.get('[data-test="graph-node-evidence"]').trigger('click');
    expect(wrapper.emitted('evidenceNavigate')?.[0]).toEqual([701]);
    await wrapper.get('[data-test="graph-edge-evidence-9"]').trigger('click');
    expect(wrapper.emitted('evidenceNavigate')?.[1]).toEqual([704]);
  });

  test('filters faction and organization nodes together', async () => {
    const wrapper = mountGraph();
    await flushPromises();

    wrapper.getComponent('[data-test="graph-type-filter"]').vm.$emit('update:modelValue', 'FACTION');
    await flushPromises();

    expect(wrapper.find('[data-test="graph-node-3"]').exists()).toBe(true);
    expect(wrapper.find('[data-test="graph-node-1"]').exists()).toBe(false);
  });

  test('shows empty, partial, and error states truthfully', async () => {
    vi.mocked(knowledgeApi.getStoryGraph).mockResolvedValueOnce({
      data: { code: 200, message: 'success', data: { nodes: [], edges: [] } },
    } as never);
    const emptyWrapper = mountGraph();
    await flushPromises();
    expect(emptyWrapper.get('[data-test="graph-empty"]').text()).toContain('暂无关系图');
    emptyWrapper.unmount();

    vi.mocked(knowledgeApi.getStoryGraph).mockResolvedValueOnce({
      data: {
        code: 200,
        message: 'success',
        data: { nodes: [{ nodeId: 8, nodeType: 'EVENT', displayName: '交付事故' }], edges: [], partial: true },
      },
    } as never);
    const partialWrapper = mountGraph();
    await flushPromises();
    expect(partialWrapper.get('[data-test="graph-partial"]').text()).toContain('部分结果');
    partialWrapper.unmount();

    vi.mocked(knowledgeApi.getStoryGraph).mockRejectedValueOnce(new Error('unavailable'));
    const errorWrapper = mountGraph();
    await flushPromises();
    expect(errorWrapper.get('[data-test="graph-error"]').text()).toContain('关系图加载失败');
  });

  test('clears old graph data and ignores a late response after scope change', async () => {
    const oldGraph = deferred<unknown>();
    vi.mocked(knowledgeApi.getStoryGraph).mockImplementation((projectId) => {
      if (projectId === 7) return oldGraph.promise as never;
      return Promise.resolve({
        data: {
          code: 200,
          message: 'success',
          data: { nodes: [{ nodeId: 90, nodeType: 'CHARACTER', displayName: '当前人物' }], edges: [] },
        },
      }) as never;
    });
    const wrapper = mountGraph();
    await wrapper.setProps({ projectId: 9, workId: 12 });
    await flushPromises();
    expect(wrapper.text()).toContain('当前人物');

    oldGraph.resolve({
      data: {
        code: 200,
        message: 'success',
        data: { nodes: [{ nodeId: 70, nodeType: 'CHARACTER', displayName: '迟到人物' }], edges: [] },
      },
    });
    await flushPromises();

    expect(wrapper.text()).toContain('当前人物');
    expect(wrapper.text()).not.toContain('迟到人物');
  });
});
