import ElementPlus from 'element-plus';
import { flushPromises, mount } from '@vue/test-utils';
import AdminAgentGovernanceView from '../AdminAgentGovernanceView.vue';
import { knowledgeApi } from '@/api/knowledge';

vi.mock('@/api/knowledge', () => ({
  knowledgeApi: {
    getAgentRuntimeConfig: vi.fn(),
    getAgentCacheTokenStats: vi.fn(),
    listAgentEvalRuns: vi.fn(),
    listAgentEvalCaseResults: vi.fn(),
    runAgentEval: vi.fn(),
    cancelAgentEvalRun: vi.fn(),
    retryAgentEvalRun: vi.fn(),
    listAgentTraces: vi.fn(),
    updateAgentRuntimeConfig: vi.fn(),
    listAgentExperts: vi.fn(),
    updateAgentExpert: vi.fn(),
  },
}));

describe('AdminAgentGovernanceView', () => {
  beforeEach(() => {
    vi.mocked(knowledgeApi.getAgentRuntimeConfig).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: {
          reasoningModeDefault: 'fast',
          maxParallelSpecialists: 3,
          maxTotalInputTokens: 48000,
          maxFinalOutputTokensFast: 4000,
          maxFinalOutputTokensDeep: 8000,
          enableIntentCache: true,
          enableTaskGraphCache: true,
          enableToolCache: true,
          enableEvidenceCache: true,
          enableSpecialistCache: false,
          maxPromptCharsPerExpert: 24000,
          maxSkillPromptChars: 12000,
          maxEvidenceItems: 30,
        },
      },
    } as never);
    vi.mocked(knowledgeApi.listAgentExperts).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: [
          {
            expertName: 'market_scan',
            displayName: 'Market Agent',
            enabled: true,
            priority: 10,
            maxTokens: 1200,
            maxToolCalls: 4,
            allowedTools: ['rank.lookup'],
            triggerIntents: ['market_scan'],
            triggerTasks: ['market_scan'],
            promptVersion: 'default',
            evalSuiteId: 'market',
            guardrail: false,
          },
          {
            expertName: 'reader_risk',
            displayName: 'Reader Risk Agent',
            enabled: true,
            priority: 900,
            maxTokens: 800,
            maxToolCalls: 2,
            allowedTools: ['skill.lookup'],
            triggerIntents: [],
            triggerTasks: ['reader_risk'],
            promptVersion: 'default',
            guardrail: true,
          },
        ],
      },
    } as never);
    vi.mocked(knowledgeApi.getAgentCacheTokenStats).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: {
          traceCount: 2,
          cacheHits: 3,
          cacheMisses: 1,
          totalTokens: 200,
          promptPrefixStableRate: 0.6667,
          tokenByNode: { route_experts: 11 },
          tokenByExpert: { market_scan: 22 },
        },
      },
    } as never);
    vi.mocked(knowledgeApi.listAgentEvalRuns).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: [
          {
            id: 1,
            runKey: 'agent-runtime:001',
            suiteName: 'agent-runtime',
            runnerName: 'worker-golden-runner',
            evaluatorName: 'rule-based',
            modelName: 'deepseek-chat',
            status: 'FAILED',
            totalCases: 2,
            passedCases: 1,
            failedCases: 1,
            progressCurrent: 1,
            progressTotal: 2,
            progressMessage: 'case 1/2 completed',
            cancelRequested: false,
            retryCount: 1,
            maxRetries: 3,
            errorMessage: 'faithfulness failed',
            metricsJson: '{"trace_completeness_rate":1.0}',
          },
          {
            id: 2,
            runKey: 'agent-runtime:002',
            suiteName: 'agent-runtime',
            runnerName: 'worker-golden-runner',
            evaluatorName: 'rule-based',
            modelName: 'deepseek-chat',
            status: 'RUNNING',
            totalCases: 10,
            passedCases: 3,
            failedCases: 0,
            progressCurrent: 3,
            progressTotal: 10,
            progressMessage: 'case 3/10 completed',
            cancelRequested: false,
            retryCount: 0,
            maxRetries: 3,
            metricsJson: '{}',
          },
        ],
      },
    } as never);
    vi.mocked(knowledgeApi.listAgentEvalCaseResults).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: [
          {
            id: 11,
            runId: 1,
            caseKey: 'mixed-001',
            status: 'FAILED',
            intent: 'mixed_creation_research',
            answerMode: 'mixed_creation',
            retrievalMetrics: '{"hit_rate_at_k":0.0}',
            faithfulnessJson: '{"passed":false}',
            failures: '["trace:missing_tool:rank.lookup"]',
            traceId: 'trace-eval-case',
            durationMs: 240,
          },
        ],
      },
    } as never);
    vi.mocked(knowledgeApi.listAgentTraces).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: {
          page: 1,
          pageSize: 1,
          total: 1,
          hasNext: false,
          items: [
            {
              id: 99,
              traceId: 'trace-governance-live',
              status: 'answered',
              resultJson: JSON.stringify({
                trace: {
                  nodes: [
                    { name: 'assemble_context', status: 'completed', sequenceNo: 1, durationMs: 12 },
                    { name: 'compose_answer', status: 'completed', sequenceNo: 2, durationMs: 240 },
                  ],
                  executedRuntimeNodes: ['assemble_context', 'compose_answer'],
                },
              }),
            },
          ],
        },
      },
    } as never);
  });

  test('loads runtime config and expert profiles', async () => {
    const wrapper = mount(AdminAgentGovernanceView, { global: { plugins: [ElementPlus] } });
    await flushPromises();

    expect(knowledgeApi.getAgentRuntimeConfig).toHaveBeenCalled();
    expect(knowledgeApi.listAgentExperts).toHaveBeenCalled();
    expect(knowledgeApi.getAgentCacheTokenStats).toHaveBeenCalled();
    expect(knowledgeApi.listAgentEvalRuns).toHaveBeenCalled();
    expect(knowledgeApi.listAgentTraces).toHaveBeenCalledWith({ page: 1, pageSize: 1 });
    expect(wrapper.text()).toContain('Agent 治理');
    expect(wrapper.text()).toContain('运行策略');
    expect(wrapper.text()).toContain('缓存与 Token');
    expect(wrapper.text()).toContain('Eval 中心');
    expect(wrapper.text()).toContain('运行拓扑');
    expect(wrapper.text()).toContain('上下文');
    expect(wrapper.text()).toContain('意图识别');
    expect(wrapper.text()).toContain('任务图');
    expect(wrapper.text()).toContain('工具与证据');
    expect(wrapper.text()).toContain('专家');
    expect(wrapper.text()).toContain('回答');
    expect(wrapper.text()).toContain('记忆与 Trace');
    expect(wrapper.text()).toContain('agent-runtime:001');
    expect(wrapper.text()).toContain('1 / 2');
    expect(wrapper.text()).toContain('case 1/2 completed');
    expect(wrapper.text()).toContain('重试 1');
    expect(wrapper.text()).toContain('faithfulness failed');
    expect(wrapper.text()).toContain('200');
    expect(wrapper.text()).toContain('提示前缀稳定率');
    expect(wrapper.text()).toContain('66.67%');
    expect(wrapper.text()).toContain('最大并行专家数');
    expect(wrapper.text()).toContain('Market Agent');
    expect(wrapper.text()).toContain('reader_risk');
    expect(wrapper.find('[data-test="governance-runtime-graph"]').exists()).toBe(true);
    expect(wrapper.text()).toContain('trace-governance-live');
    expect(wrapper.text()).toContain('compose_answer');
    expect(wrapper.text()).toContain('worker');
    expect(wrapper.text()).toContain('Agent Trace');
  });

  test('saves runtime and expert profile changes', async () => {
    vi.mocked(knowledgeApi.updateAgentRuntimeConfig).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: {
          reasoningModeDefault: 'fast',
          maxParallelSpecialists: 5,
        },
      },
    } as never);
    vi.mocked(knowledgeApi.updateAgentExpert).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: {
          expertName: 'market_scan',
          displayName: 'Market Agent',
          enabled: false,
          priority: 10,
          maxTokens: 1200,
          maxToolCalls: 4,
          allowedTools: ['rank.lookup'],
          triggerIntents: ['market_scan'],
          triggerTasks: ['market_scan'],
          promptVersion: 'default',
          evalSuiteId: 'market',
          guardrail: false,
        },
      },
    } as never);

    const wrapper = mount(AdminAgentGovernanceView, { global: { plugins: [ElementPlus] } });
    await flushPromises();

    await wrapper.find('[data-test="save-runtime-maxParallelSpecialists"]').trigger('click');
    await wrapper.find('[data-test="expert-enabled-market_scan"]').setValue(false);
    await wrapper.find('[data-test="save-expert-market_scan"]').trigger('click');
    await flushPromises();

    expect(knowledgeApi.updateAgentRuntimeConfig).toHaveBeenCalledWith('maxParallelSpecialists', { value: '3' });
    expect(knowledgeApi.updateAgentExpert).toHaveBeenCalledWith('market_scan', expect.objectContaining({ enabled: false }));
    expect(wrapper.text()).toContain('已保存');
  });

  test('starts an eval suite run from the admin eval center', async () => {
    vi.mocked(knowledgeApi.runAgentEval).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: {
          id: 42,
          runKey: 'agent-runtime:manual-001',
          suiteName: 'agent-runtime',
          runnerName: 'admin-trigger',
          evaluatorName: 'rule-based',
          modelName: 'deepseek-chat',
          status: 'RUNNING',
          totalCases: 10,
          passedCases: 0,
          failedCases: 0,
        },
      },
    } as never);
    const wrapper = mount(AdminAgentGovernanceView, { global: { plugins: [ElementPlus] } });
    await flushPromises();
    const callsAfterInitialLoad = vi.mocked(knowledgeApi.listAgentEvalRuns).mock.calls.length;

    await wrapper.find('[data-test="eval-suite-name"]').setValue('agent-runtime');
    await wrapper.find('[data-test="eval-case-limit"]').setValue(10);
    await wrapper.find('[data-test="run-eval-suite"]').trigger('click');
    await flushPromises();

    expect(knowledgeApi.runAgentEval).toHaveBeenCalledWith(
      expect.objectContaining({
        suiteName: 'agent-runtime',
        runnerName: 'admin-trigger',
        evaluatorName: 'rule-based',
        caseLimit: 10,
      }),
    );
    expect(knowledgeApi.listAgentEvalRuns).toHaveBeenCalledTimes(callsAfterInitialLoad + 1);
    expect(wrapper.text()).toContain('Eval 任务已启动');
  });

  test('loads eval case results for a selected run', async () => {
    const wrapper = mount(AdminAgentGovernanceView, { global: { plugins: [ElementPlus] } });
    await flushPromises();

    await wrapper.find('[data-test="view-eval-cases-1"]').trigger('click');
    await flushPromises();

    expect(knowledgeApi.listAgentEvalCaseResults).toHaveBeenCalledWith(1);
    expect(wrapper.text()).toContain('mixed-001');
    expect(wrapper.text()).toContain('trace-eval-case');
    expect(wrapper.text()).toContain('missing_tool');
  });

  test('cancels and retries eval runs from the admin eval center', async () => {
    vi.mocked(knowledgeApi.cancelAgentEvalRun).mockResolvedValue({
      data: { code: 200, message: 'success', data: { id: 1, status: 'CANCELLED' } },
    } as never);
    vi.mocked(knowledgeApi.retryAgentEvalRun).mockResolvedValue({
      data: { code: 200, message: 'success', data: { id: 1, status: 'QUEUED', retryCount: 2 } },
    } as never);
    const wrapper = mount(AdminAgentGovernanceView, { global: { plugins: [ElementPlus] } });
    await flushPromises();

    await wrapper.find('[data-test="cancel-eval-run-2"]').trigger('click');
    await flushPromises();
    await wrapper.find('[data-test="retry-eval-run-1"]').trigger('click');
    await flushPromises();

    expect(knowledgeApi.cancelAgentEvalRun).toHaveBeenCalledWith(2);
    expect(knowledgeApi.retryAgentEvalRun).toHaveBeenCalledWith(1);
    expect(wrapper.text()).toContain('Eval 任务已更新');
  });
});
