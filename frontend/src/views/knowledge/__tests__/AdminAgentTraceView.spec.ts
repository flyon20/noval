import ElementPlus from 'element-plus';
import { flushPromises, mount } from '@vue/test-utils';
import AdminAgentTraceView from '../AdminAgentTraceView.vue';
import { knowledgeApi } from '@/api/knowledge';

vi.mock('@/api/knowledge', () => ({
  knowledgeApi: {
    listAgentTraces: vi.fn(),
    getAgentTrace: vi.fn(),
  },
}));

describe('AdminAgentTraceView', () => {
  test('renders trace summary and selected detail', async () => {
    vi.mocked(knowledgeApi.listAgentTraces).mockResolvedValue({
      data: { code: 200, message: 'success', data: [{ id: 1, traceId: 'trace-1', status: 'answered' }] },
    } as never);
    vi.mocked(knowledgeApi.getAgentTrace).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: {
          id: 1,
          traceId: 'trace-1',
          question: '扫榜后做题材',
          taskGraph: '{"tasks":[{"type":"market_scan"}]}',
          toolRuns: '[{"name":"rank.lookup"}]',
          evidencePack: '{"factCount":1}',
          perspectiveResults: '[{"perspective":"market"}]',
          intentDecision: '{"primaryIntent":"market_scan"}',
          contextUsed: '{"projectMemoryKeys":["premise"]}',
          memoryUsed: '{"project":true}',
          sourcePolicy: '{"freshness":"latest","snapshotTime":"2026-06-22T00:00:00"}',
          supervisorDecision: '{"status":"answerable"}',
          memoryCandidates: '[{"scope":"project","content":"likes fast starts"}]',
          snapshotTime: '2026-06-22T00:00:00',
        },
      },
    } as never);

    const wrapper = mount(AdminAgentTraceView, { global: { plugins: [ElementPlus] } });
    await flushPromises();

    expect(wrapper.text()).toContain('trace-1');
    expect(wrapper.text()).toContain('market_scan');
    expect(wrapper.text()).toContain('rank.lookup');
    expect(wrapper.text()).toContain('Facts');
    expect(wrapper.text()).toContain('market');
    expect(wrapper.text()).toContain('Intent Decision');
    expect(wrapper.text()).toContain('Source Policy');
    expect(wrapper.text()).toContain('Context Used');
    expect(wrapper.text()).toContain('Memory Used');
    expect(wrapper.text()).toContain('Supervisor Decision');
    expect(wrapper.text()).toContain('Memory Candidates');
    expect(wrapper.text()).toContain('2026-06-22T00:00:00');
    expect(wrapper.text()).toContain('likes fast starts');
  });
});
