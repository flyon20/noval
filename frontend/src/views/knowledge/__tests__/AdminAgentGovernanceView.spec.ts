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
    getAgentTrace: vi.fn(),
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
          maxParallelSpecialists: 1,
          maxTotalInputTokens: 48000,
          maxFinalOutputTokensFast: 4000,
          maxFinalOutputTokensDeep: 8000,
          enableIntentCache: true,
          enableTaskGraphCache: true,
          enableToolCache: true,
          enableEvidenceCache: true,
          enableSpecialistCache: false,
          specialistMcpEnabled: false,
          maxPromptCharsPerExpert: 24000,
          maxSkillPromptChars: 0,
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
            requestedToolCapabilities: ['market.read'],
            triggerIntents: ['market_scan'],
            triggerTasks: ['market_scan'],
            promptVersion: 'default',
            evalSuiteId: 'market',
            guardrail: false,
            category: 'Skill',
          executionKind: 'INLINE',
          expectedQualityGain: 0,
          qualityGainVerified: false,
          qualityGainSource: 'not_required',
            latencyCost: 0,
            tokenCost: 0,
            resourceCost: 0,
          },
          {
            expertName: 'reader_risk',
            displayName: 'Reader Risk Agent',
            enabled: true,
            priority: 900,
            maxTokens: 800,
            maxToolCalls: 2,
            requestedToolCapabilities: ['skill.activate'],
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
            },
          ],
        },
      },
    } as never);
    vi.mocked(knowledgeApi.getAgentTrace).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: {
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
    expect(knowledgeApi.getAgentTrace).toHaveBeenCalledWith(99);
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
    expect(wrapper.text()).toContain('已完成用例 1 / 2');
    expect(wrapper.text()).toContain('重试 1');
    expect(wrapper.text()).toContain('faithfulness failed');
    expect(wrapper.text()).toContain('200');
    expect(wrapper.text()).toContain('提示前缀稳定率');
    expect(wrapper.text()).toContain('66.67%');
    expect(wrapper.text()).toContain('最大并行专家数');
    expect(wrapper.text()).toContain('0 表示按模型窗口和任务类型自动计算；正数只作为快速模式最终回答 Token 上限。');
    expect(wrapper.text()).toContain('0 表示按模型窗口和任务类型自动计算；正数只作为深度模式最终回答 Token 上限。');
    expect(wrapper.text()).toContain('0 表示按模型窗口和实际专家数自动分配；正数只作为每个专家完整提示上下文的治理上限。');
    expect(wrapper.text()).toContain('0 表示不设置独立 Skill 字符上限');
    expect(wrapper.text()).toContain('专家工具调用');
    expect((wrapper.get('input[aria-label="专家工具调用"]').element as HTMLInputElement).checked).toBe(false);
    expect(wrapper.text()).toContain('市场扫描专家');
    expect(wrapper.text()).toContain('reader_risk');
    expect(wrapper.find('[data-test="governance-runtime-graph"]').exists()).toBe(true);
    expect(wrapper.text()).toContain('trace-governance-live');
    expect(wrapper.text()).toContain('生成回答');
    expect(wrapper.text()).toContain('worker');
    expect(wrapper.text()).toContain('Agent Trace');
    expect(wrapper.text()).not.toContain('控制平面模式');
    expect(wrapper.text()).toContain('失败');
    expect(wrapper.text()).toContain('运行中');
    expect(wrapper.text()).toContain('快速模式');
    expect(wrapper.text()).toContain('技能能力');
    expect(wrapper.text()).not.toContain('RUNNING');
    expect(wrapper.text()).not.toContain('FAILED');
  }, 15_000);

  test('saves runtime and expert profile changes', async () => {
    vi.mocked(knowledgeApi.updateAgentRuntimeConfig).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: {
          reasoningModeDefault: 'fast',
          maxParallelSpecialists: 1,
          specialistMcpEnabled: true,
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
          requestedToolCapabilities: ['market.read'],
          triggerIntents: ['market_scan'],
          triggerTasks: ['market_scan'],
          promptVersion: 'default',
          evalSuiteId: 'market',
          guardrail: false,
          category: 'Delegated',
          executionKind: 'DELEGATED',
          expectedQualityGain: 0.5,
          qualityGainVerified: true,
          qualityGainSource: 'approved_eval',
          qualityGainEvalRunId: 42,
          latencyCost: 0.1,
          tokenCost: 0.05,
          resourceCost: 0.05,
        },
      },
    } as never);

    const wrapper = mount(AdminAgentGovernanceView, { global: { plugins: [ElementPlus] } });
    await flushPromises();

    await wrapper.find('[data-test="save-runtime-maxParallelSpecialists"]').trigger('click');
    await wrapper.get('input[aria-label="专家工具调用"]').setValue(true);
    await wrapper.find('[data-test="save-runtime-specialistMcpEnabled"]').trigger('click');
    expect((wrapper.get('input[aria-label="受限证据修复"]').element as HTMLInputElement).checked).toBe(false);
    await wrapper.get('input[aria-label="终稿规则复核"]').setValue(true);
    await wrapper.get('[data-test="save-runtime-harnessAnswerValidationEnabled"]').trigger('click');
    await wrapper.find('[data-test="expert-enabled-market_scan"]').setValue(false);
    await wrapper.find('[data-test="expert-category-market_scan"]').setValue('Delegated');
    await wrapper.find('[data-test="save-expert-market_scan"]').trigger('click');
    await flushPromises();

    expect(knowledgeApi.updateAgentRuntimeConfig).toHaveBeenCalledWith('maxParallelSpecialists', { value: '1' });
    expect(knowledgeApi.updateAgentRuntimeConfig).toHaveBeenCalledWith('specialistMcpEnabled', { value: 'true' });
    expect(knowledgeApi.updateAgentRuntimeConfig).toHaveBeenCalledWith('harnessAnswerValidationEnabled', { value: 'true' });
    expect((wrapper.get('input[aria-label="专家工具调用"]').element as HTMLInputElement).checked).toBe(true);
    expect(knowledgeApi.updateAgentExpert).toHaveBeenCalledWith('market_scan', expect.objectContaining({
      enabled: false,
      category: 'Delegated',
      executionKind: 'DELEGATED',
      expectedQualityGain: 0,
    }));
    const expertPayload = vi.mocked(knowledgeApi.updateAgentExpert).mock.calls[0]?.[1];
    expect(expertPayload).not.toHaveProperty('allowedTools');
    expect(expertPayload).toHaveProperty('requestedToolCapabilities', ['market.read']);
    expect(wrapper.text()).toContain('已保存');
  }, 15_000);

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
