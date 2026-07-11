import ElementPlus from 'element-plus';
import { mount } from '@vue/test-utils';
import LangGraphRuntimeGraph from '../LangGraphRuntimeGraph.vue';

function runtimePayload() {
  return JSON.stringify({
    trace: {
      executedRuntimeNodes: ['assemble_context', 'classify_intent', 'compose_answer'],
      nodes: [
        {
          name: 'assemble_context',
          status: 'completed',
          sequenceNo: 1,
          durationMs: 12,
          input: { projectId: 7 },
          output: { contextStatus: 'loaded' },
        },
        {
          name: 'classify_intent',
          status: 'completed',
          sequenceNo: 2,
          durationMs: 8,
          input: { question: 'market scan' },
          output: { intent: 'market_scan' },
        },
        {
          name: 'compose_answer',
          status: 'failed',
          sequenceNo: 3,
          durationMs: 74,
          error: 'missing evidence contract',
          input: { sources: [] },
          output: { answerStatus: 'insufficient_evidence' },
        },
      ],
    },
  });
}

describe('LangGraphRuntimeGraph', () => {
  test('renders directed path, filters by node status, and opens node details', async () => {
    const wrapper = mount(LangGraphRuntimeGraph, {
      props: {
        resultJson: runtimePayload(),
      },
      global: {
        plugins: [ElementPlus],
      },
    });

    expect(wrapper.text()).toContain('运行路径');
    expect(wrapper.findAll('[data-test="runtime-edge"]')).toHaveLength(2);
    expect(wrapper.find('[data-test="runtime-node-card-assemble_context"]').exists()).toBe(true);
    expect(wrapper.find('[data-test="runtime-node-card-compose_answer"]').exists()).toBe(true);

    await wrapper.find('[data-test="runtime-filter-failed"]').trigger('click');

    expect(wrapper.find('[data-test="runtime-node-card-compose_answer"]').exists()).toBe(true);
    expect(wrapper.find('[data-test="runtime-node-card-assemble_context"]').exists()).toBe(false);

    await wrapper.find('[data-test="runtime-node-card-compose_answer"]').trigger('click');

    expect(wrapper.find('[data-test="runtime-node-detail"]').exists()).toBe(true);
    expect(wrapper.text()).toContain('节点详情');
    expect(wrapper.text()).toContain('compose_answer');
    expect(wrapper.text()).toContain('missing evidence contract');
    expect(wrapper.text()).toContain('节点原始 JSON');
  });

  test('marks old traces without executedRuntimeNodes as compatibility inference', () => {
    const wrapper = mount(LangGraphRuntimeGraph, {
      props: {
        resultJson: JSON.stringify({
          trace: {
            nodes: [
              { name: 'assemble_context', status: 'completed', sequenceNo: 1 },
              { name: 'classify_intent', status: 'completed', sequenceNo: 2 },
              { name: 'route_experts', status: 'skipped', sequenceNo: 3 },
            ],
          },
        }),
      },
      global: {
        plugins: [ElementPlus],
      },
    });

    expect(wrapper.text()).toContain('3 个节点');
    expect(wrapper.text()).toContain('2 个兼容推断');
    expect(wrapper.text()).toContain('旧 Trace 未记录真实执行集合');
  });
});
