import ElementPlus from 'element-plus';
import { flushPromises, mount } from '@vue/test-utils';
import AdminAgentTraceView from '../AdminAgentTraceView.vue';
import { knowledgeApi } from '@/api/knowledge';

vi.mock('@/api/knowledge', () => ({
  knowledgeApi: {
    listAgentTraces: vi.fn(),
    getAgentTrace: vi.fn(),
    createGoldenCandidate: vi.fn(),
  },
}));

describe('AdminAgentTraceView', () => {
  test('renders trace list first and opens Chinese detail after row click', async () => {
    vi.mocked(knowledgeApi.listAgentTraces).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: {
          page: 1,
          pageSize: 20,
          total: 21,
          hasNext: true,
          items: [{
            id: 1,
            traceId: 'trace-1',
            status: 'answered',
            question: '榜单趋势',
            resultJson: '{"trace":{"health":{"model":"fallback_used","tools":"blocked","evidence":"succeeded","memory":"skipped","experts":"succeeded"},"providerCalls":[{"status":"failed","model":"deepseek-chat"}]}}',
          }],
        },
      },
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
          memoryDiagnostics: '{"layers":{"projectMemory":{"status":"loaded"}}}',
          retrievalDiagnostics: '{"selectedCount":2,"reasonTags":["trend_quota_selection"]}',
          resultJson:
            '{"trace":{"health":{"model":"fallback_used","tools":"blocked","evidence":"succeeded","memory":"skipped","experts":"succeeded"},"projectKnowledge":{"projectId":910,"workId":920,"retrievedChapters":[{"chapterNo":12,"title":"delivery"}],"retrievedChunks":[{"sourceType":"scene","chunkText":"admin signal chunk"}],"matchedForeshadowings":[{"title":"moon-admin","status":"OPEN"}]},"providerCalls":[{"status":"failed","model":"deepseek-chat"}],"executedRuntimeNodes":["assemble_context","classify_intent","compose_answer"],"nodes":[{"name":"assemble_context","status":"completed","sequenceNo":1,"durationMs":12},{"name":"classify_intent","status":"completed","sequenceNo":2,"durationMs":8},{"name":"validate_preconditions","status":"skipped","sequenceNo":3},{"name":"compose_answer","status":"completed","sequenceNo":4,"durationMs":54}]}}',
          sourcePolicy: '{"freshness":"latest","snapshotTime":"2026-06-22T00:00:00"}',
          supervisorDecision: '{"status":"answerable"}',
          memoryCandidates: '[{"scope":"project","content":"likes fast starts"}]',
          mcpToolCalls: '[{"name":"rank.lookup","status":"succeeded"}]',
          toolPermissionDecisions: '[{"tool":"rank.lookup","allowed":true}]',
          evidenceContract: '{"status":"verified_latest"}',
          selectedSnapshotGroup: '{"snapshotTime":"2026-06-22T00:00:00","source":"rank.research_pack"}',
          rejectedSnapshotGroups: '[{"snapshotTime":"2026-06-21T00:00:00","source":"rank.lookup"}]',
          specialistAgentResults: '[{"agentName":"OutlineAgent","status":"completed"}]',
          selectedExperts:
            '[{"name":"market_scan","reason":"intent:mixed_creation_research","reasonTags":["intent:mixed_creation_research"]}]',
          expertRouter:
            '{"reasoningMode":"fast","maxParallel":3,"selectedExperts":[{"name":"market_scan","reason":"intent:mixed_creation_research"}]}',
          finalAnswerBoundary: '{"status":"bounded"}',
          snapshotTime: '2026-06-22T00:00:00',
        },
      },
    } as never);

    const wrapper = mount(AdminAgentTraceView, { global: { plugins: [ElementPlus] } });
    await flushPromises();

    expect(knowledgeApi.listAgentTraces).toHaveBeenCalledWith({ page: 1, pageSize: 20 });
    expect(knowledgeApi.getAgentTrace).not.toHaveBeenCalled();
    expect(wrapper.text()).toContain('智能体 Trace');
    expect(wrapper.text()).toContain('trace-1');
    expect(wrapper.text()).toContain('共 21 条');
    expect(wrapper.text()).toContain('请选择一条 Trace 记录');
    expect(wrapper.text()).toContain('模型');
    expect(wrapper.text()).toContain('fallback_used');
    expect(wrapper.find('[data-test="trace-health-blocks"]').exists()).toBe(true);
    expect(wrapper.text()).not.toContain('意图决策');
    expect(wrapper.text()).not.toContain('rank.lookup');

    await wrapper.find('[data-test="trace-row"]').trigger('click');
    await flushPromises();

    expect(knowledgeApi.getAgentTrace).toHaveBeenCalledWith(1);
    expect(wrapper.text()).toContain('market_scan');
    expect(wrapper.text()).toContain('rank.lookup');
    expect(wrapper.text()).toContain('Facts');
    expect(wrapper.text()).toContain('market');
    expect(wrapper.text()).toContain('意图决策');
    expect(wrapper.text()).toContain('来源策略');
    expect(wrapper.text()).toContain('上下文使用');
    expect(wrapper.text()).toContain('记忆使用');
    expect(wrapper.text()).toContain('记忆诊断');
    expect(wrapper.text()).toContain('检索诊断');
    expect(wrapper.text()).toContain('作品知识库');
    expect(wrapper.text()).toContain('P910');
    expect(wrapper.text()).toContain('W920');
    expect(wrapper.text()).toContain('delivery');
    expect(wrapper.text()).toContain('检索片段');
    expect(wrapper.text()).toContain('admin signal chunk');
    expect(wrapper.text()).toContain('moon-admin');
    expect(wrapper.text()).toContain('LangGraph 运行图');
    expect(wrapper.text()).toContain('assemble_context');
    expect(wrapper.text()).toContain('validate_preconditions');
    expect(wrapper.text()).toContain('已跳过');
    expect(wrapper.text()).toContain('4 个节点');
    expect(wrapper.text()).toContain('3 个真实执行');
    expect(wrapper.text()).toContain('监督决策');
    expect(wrapper.text()).toContain('记忆候选');
    expect(wrapper.text()).toContain('MCP 工具调用');
    expect(wrapper.text()).toContain('工具权限');
    expect(wrapper.text()).toContain('证据契约');
    expect(wrapper.text()).toContain('快照仲裁');
    expect(wrapper.text()).toContain('专家交接');
    expect(wrapper.text()).toContain('专家路由');
    expect(wrapper.text()).toContain('最终回答边界');
    expect(wrapper.text()).toContain('verified_latest');
    expect(wrapper.text()).toContain('OutlineAgent');
    expect(wrapper.text()).toContain('market_scan');
    expect(wrapper.text()).toContain('intent:mixed_creation_research');
    expect(wrapper.text()).toContain('2026-06-22T00:00:00');
    expect(wrapper.text()).toContain('likes fast starts');
    expect(wrapper.text()).toContain('projectMemory');
    expect(wrapper.text()).toContain('trend_quota_selection');
    expect(wrapper.find('.trace-overview').exists()).toBe(true);
    expect(wrapper.text()).toContain('Trace 健康');
    expect(wrapper.classes()).toContain('admin-agent-trace--focus-detail');
    expect(wrapper.find('[data-test="trace-back-to-list"]').exists()).toBe(true);
  });

  test('creates a draft golden candidate from selected trace', async () => {
    vi.mocked(knowledgeApi.listAgentTraces).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: {
          page: 1,
          pageSize: 20,
          total: 1,
          hasNext: false,
          items: [{ id: 9, traceId: 'trace-golden', status: 'answered', question: '生成 golden' }],
        },
      },
    } as never);
    vi.mocked(knowledgeApi.getAgentTrace).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: { id: 9, traceId: 'trace-golden', question: '生成 golden' },
      },
    } as never);
    vi.mocked(knowledgeApi.createGoldenCandidate).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: { status: 'DRAFT', traceId: 'trace-golden', question: '生成 golden' },
      },
    } as never);

    const wrapper = mount(AdminAgentTraceView, { global: { plugins: [ElementPlus] } });
    await flushPromises();

    await wrapper.find('[data-test="trace-row"]').trigger('click');
    await flushPromises();
    await wrapper.find('[data-test="create-golden-candidate"]').trigger('click');
    await flushPromises();

    expect(knowledgeApi.createGoldenCandidate).toHaveBeenCalledWith(9);
    expect(wrapper.text()).toContain('Golden 候选已创建');
    expect(wrapper.text()).toContain('DRAFT');
  });

  test('loads next trace page without fetching heavy JSON in list', async () => {
    vi.mocked(knowledgeApi.listAgentTraces)
      .mockResolvedValueOnce({
        data: {
          code: 200,
          message: 'success',
          data: {
            page: 1,
            pageSize: 20,
            total: 40,
            hasNext: true,
            items: [{ id: 1, traceId: 'trace-1', status: 'answered', question: 'first page' }],
          },
        },
      } as never)
      .mockResolvedValueOnce({
        data: {
          code: 200,
          message: 'success',
          data: {
            page: 2,
            pageSize: 20,
            total: 40,
            hasNext: false,
            items: [{ id: 2, traceId: 'trace-2', status: 'answered', question: 'second page' }],
          },
        },
      } as never);
    vi.mocked(knowledgeApi.getAgentTrace)
      .mockResolvedValueOnce({
        data: { code: 200, message: 'success', data: { id: 1, traceId: 'trace-1', question: 'first page' } },
      } as never)
      .mockResolvedValueOnce({
        data: { code: 200, message: 'success', data: { id: 2, traceId: 'trace-2', question: 'second page' } },
      } as never);

    const wrapper = mount(AdminAgentTraceView, { global: { plugins: [ElementPlus] } });
    await flushPromises();

    await wrapper.find('[data-test="trace-next-page"]').trigger('click');
    await flushPromises();

    expect(knowledgeApi.listAgentTraces).toHaveBeenLastCalledWith({ page: 2, pageSize: 20 });
    expect(wrapper.text()).toContain('trace-2');
    expect(wrapper.text()).not.toContain('taskGraph');
  });
});
