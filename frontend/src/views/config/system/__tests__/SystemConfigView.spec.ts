import ElementPlus from 'element-plus';
import { ElMessage } from 'element-plus';
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils';
import { createMemoryHistory, createRouter } from 'vue-router';
import SystemConfigView from '../SystemConfigView.vue';

vi.mock('@/api/config', () => ({
  promptConfigApi: {
    getByType: vi.fn(),
    listTemplates: vi.fn(),
    update: vi.fn(),
  },
  systemConfigApi: {
    listKnown: vi.fn(),
    getByKey: vi.fn(),
    update: vi.fn(),
    getModelRegistry: vi.fn(),
    updateModelRegistry: vi.fn(),
    probeModelProvider: vi.fn(),
    listPromptTemplates: vi.fn(),
  },
}));

function createSystemConfig(
  configKey: string,
  configValue: string,
  overrides: Partial<{
    configType: string;
    description: string;
    editable: boolean;
  }> = {},
) {
  return {
    id: 1,
    configKey,
    configValue,
    configType: overrides.configType ?? 'string',
    description: overrides.description ?? `${configKey} description`,
    editable: overrides.editable ?? true,
  };
}

enableAutoUnmount(afterEach);

describe('SystemConfigView', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  test('loads fixed system config keys on mount', async () => {
    const { systemConfigApi } = await import('@/api/config');
    const expectedKeys = [
      'ai.provider.type',
      'ai.timeout.millis',
      'analysis.runtime.mode',
      'ai.openai-compatible.base-url',
      'ai.openai-compatible.default-model',
      'ai.openai-compatible.api-key',
      'ai.openai-compatible.streaming-enabled',
      'ai.langgraph-worker.base-url',
      'ai.langgraph-worker.internal-api-key',
      'ai.langgraph-worker.timeout-millis',
      'ai.knowledge.reasoning-mode.default',
      'ai.available-models',
      'analysis.reanalyze.cooldown-hours',
      'analysis.reanalyze.user-max-times',
      'analysis.chunk.max-input-tokens',
      'analysis.chunk.target-input-tokens',
      'analysis.chunk.parallelism',
      'auth.bootstrap-admin-phones',
      'crawler.default.chapter-count',
      'crawler.http.timeout-seconds',
      'crawler.chapter.fetch-workers',
      'crawler.chapter.force-refresh.user-max-times',
      'crawler.rank.refresh-days',
      'crawler.rank.force-cooldown-days',
      'crawler.rank.force-max-times',
      'crawler.book.refresh-days',
      'security.audit.enabled',
    ];

    vi.mocked(systemConfigApi.getByKey).mockImplementation(async (configKey) => ({
      data: {
        code: 200,
        message: 'success',
        data: createSystemConfig(configKey, 'demo-value'),
        timestamp: 1,
        traceId: `trace-${configKey}`,
      },
    }));
    vi.mocked(systemConfigApi.getModelRegistry).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: {
          defaultModelKey: 'deepseek-chat',
          models: [
            {
              modelKey: 'deepseek-chat',
              displayName: 'DeepSeek Chat',
              providerType: 'openai-compatible',
              modelName: 'deepseek-chat',
              baseUrl: 'https://api.deepseek.com/v1',
              apiKey: null,
              apiKeyConfigured: true,
              apiKeyMasked: '已配置',
              enabled: true,
              isDefault: true,
              defaultTemperature: 1,
              maxTokens: 8192,
              temperatureSpecJson: '{"min":0,"max":2}',
              promptBindings: {
                deconstruct: 'deepseek-chat',
                structure: 'deepseek-chat',
                plot: 'deepseek-chat',
                theme: 'default',
              },
            },
          ],
        },
        timestamp: 1,
        traceId: 'trace-registry',
      },
    });
    vi.mocked(systemConfigApi.listPromptTemplates).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: [
          { id: 1, promptType: 'deconstruct', promptName: 'default', modelName: 'deepseek-chat' },
          { id: 2, promptType: 'structure', promptName: 'default', modelName: 'deepseek-chat' },
          { id: 3, promptType: 'plot', promptName: 'default', modelName: 'deepseek-chat' },
          { id: 4, promptType: 'theme', promptName: 'default', modelName: 'deepseek-chat' },
        ],
        timestamp: 1,
        traceId: 'trace-templates',
      },
    });

    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/config/system', component: SystemConfigView }],
    });
    await router.push('/config/system');

    const wrapper = mount(SystemConfigView, {
      global: {
        plugins: [router, ElementPlus],
      },
    });

    await flushPromises();

    expect(systemConfigApi.getModelRegistry).toHaveBeenCalledTimes(1);
    expect(systemConfigApi.listKnown).toHaveBeenCalledTimes(1);
    expect(systemConfigApi.getByKey).toHaveBeenCalledTimes(expectedKeys.length);
    expectedKeys.forEach((configKey) => {
      expect(systemConfigApi.getByKey).toHaveBeenCalledWith(configKey);
    });
    expect(wrapper.text()).toContain('ai.provider.type');
    expect(wrapper.text()).toContain('DeepSeek Chat');
    expect(wrapper.get('[data-test="provider-routing-status"]').text()).toContain('已关闭');
  }, 30_000);

  test('loads backend known system config list before fixed fallback keys', async () => {
    const { systemConfigApi } = await import('@/api/config');

    vi.mocked(systemConfigApi.listKnown).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: [
          createSystemConfig('ai.timeout.millis', '15000', {
            description: '控制 AI 请求超时。',
          }),
          createSystemConfig('crawler.rank.refresh-days', '5', {
            description: '榜单缓存天数。',
          }),
        ],
        timestamp: 1,
        traceId: 'trace-known',
      },
    });
    vi.mocked(systemConfigApi.getByKey).mockImplementation(async (configKey) => ({
      data: {
        code: 200,
        message: 'success',
        data: createSystemConfig(configKey, 'demo-value'),
        timestamp: 1,
        traceId: `trace-${configKey}`,
      },
    }));
    vi.mocked(systemConfigApi.getModelRegistry).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: { defaultModelKey: '', models: [] },
        timestamp: 1,
        traceId: 'trace-registry',
      },
    });
    vi.mocked(systemConfigApi.listPromptTemplates).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: [],
        timestamp: 1,
        traceId: 'trace-templates',
      },
    });

    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/config/system', component: SystemConfigView }],
    });
    await router.push('/config/system');

    const wrapper = mount(SystemConfigView, {
      global: {
        plugins: [router, ElementPlus],
      },
    });

    await flushPromises();

    expect(systemConfigApi.listKnown).toHaveBeenCalledTimes(1);
    expect(systemConfigApi.getByKey).toHaveBeenCalledTimes(2);
    expect(systemConfigApi.getByKey).toHaveBeenNthCalledWith(1, 'ai.timeout.millis');
    expect(systemConfigApi.getByKey).toHaveBeenNthCalledWith(2, 'crawler.rank.refresh-days');
    expect(wrapper.text()).toContain('控制单书分析等常规 AI 请求超时时间。');
    expect(wrapper.text()).not.toContain('Current Page');
    expect(wrapper.text()).not.toContain('Model Registry');
    expect(wrapper.text()).not.toContain('Runtime Config');
    expect(wrapper.text()).not.toContain('Editable');
    expect(wrapper.text()).not.toContain('Read Only');
    expect(wrapper.text()).not.toContain('No extra description.');
  }, 30_000);

  test('falls back to fixed system config keys when backend known list is unavailable', async () => {
    const { systemConfigApi } = await import('@/api/config');

    vi.mocked(systemConfigApi.listKnown).mockRejectedValue(new Error('known endpoint missing'));
    vi.mocked(systemConfigApi.getByKey).mockImplementation(async (configKey) => ({
      data: {
        code: 200,
        message: 'success',
        data: createSystemConfig(configKey, 'demo-value'),
        timestamp: 1,
        traceId: `trace-${configKey}`,
      },
    }));
    vi.mocked(systemConfigApi.getModelRegistry).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: { defaultModelKey: '', models: [] },
        timestamp: 1,
        traceId: 'trace-registry',
      },
    });
    vi.mocked(systemConfigApi.listPromptTemplates).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: [],
        timestamp: 1,
        traceId: 'trace-templates',
      },
    });

    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/config/system', component: SystemConfigView }],
    });
    await router.push('/config/system');

    mount(SystemConfigView, {
      global: {
        plugins: [router, ElementPlus],
      },
    });

    await flushPromises();

    expect(systemConfigApi.listKnown).toHaveBeenCalledTimes(1);
    expect(systemConfigApi.getByKey).toHaveBeenCalledWith('ai.provider.type');
    expect(systemConfigApi.getByKey).toHaveBeenCalledWith('crawler.rank.refresh-days');
  }, 30_000);

  test('updates editable system config item', async () => {
    const { systemConfigApi } = await import('@/api/config');

    vi.mocked(systemConfigApi.getByKey).mockImplementation(async (configKey) => ({
      data: {
        code: 200,
        message: 'success',
        data: createSystemConfig(configKey, configKey === 'security.audit.enabled' ? 'true' : '1000'),
        timestamp: 1,
        traceId: `trace-${configKey}`,
      },
    }));
    vi.mocked(systemConfigApi.getModelRegistry).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: {
          defaultModelKey: 'deepseek-chat',
          models: [
            {
              modelKey: 'deepseek-chat',
              displayName: 'DeepSeek Chat',
              providerType: 'openai-compatible',
              modelName: 'deepseek-chat',
              baseUrl: 'https://api.deepseek.com/v1',
              apiKey: null,
              apiKeyConfigured: true,
              apiKeyMasked: '已配置',
              enabled: true,
              isDefault: true,
              defaultTemperature: 1,
              maxTokens: 8192,
              temperatureSpecJson: '{"min":0,"max":2}',
              promptBindings: {
                deconstruct: 'deepseek-chat',
                structure: 'deepseek-chat',
                plot: 'deepseek-chat',
                theme: 'default',
              },
            },
          ],
        },
        timestamp: 1,
        traceId: 'trace-registry',
      },
    });
    vi.mocked(systemConfigApi.listPromptTemplates).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: [
          { id: 1, promptType: 'deconstruct', promptName: 'default', modelName: 'deepseek-chat' },
          { id: 2, promptType: 'structure', promptName: 'default', modelName: 'deepseek-chat' },
          { id: 3, promptType: 'plot', promptName: 'default', modelName: 'deepseek-chat' },
          { id: 4, promptType: 'theme', promptName: 'default', modelName: 'deepseek-chat' },
        ],
        timestamp: 1,
        traceId: 'trace-templates',
      },
    });
    vi.mocked(systemConfigApi.update).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: createSystemConfig('ai.timeout.millis', '2000'),
        timestamp: 1,
        traceId: 'trace-update',
      },
    });

    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/config/system', component: SystemConfigView }],
    });
    await router.push('/config/system');

    const wrapper = mount(SystemConfigView, {
      global: {
        plugins: [router, ElementPlus],
      },
    });

    await flushPromises();

    await wrapper.get('[data-test="system-config-value-ai.timeout.millis"]').setValue('2000');
    await wrapper.get('[data-test="system-config-save-ai.timeout.millis"]').trigger('click');
    await flushPromises();

    expect(systemConfigApi.update).toHaveBeenCalledWith({
      configKey: 'ai.timeout.millis',
      configValue: '2000',
      configType: 'string',
      description: 'ai.timeout.millis description',
    });
  }, 30_000);

  test('updates AI knowledge default reasoning mode from segmented control', async () => {
    const { systemConfigApi } = await import('@/api/config');

    vi.mocked(systemConfigApi.getByKey).mockImplementation(async (configKey) => ({
      data: {
        code: 200,
        message: 'success',
        data: createSystemConfig(configKey, configKey === 'ai.knowledge.reasoning-mode.default' ? 'fast' : '1000'),
        timestamp: 1,
        traceId: `trace-${configKey}`,
      },
    }));
    vi.mocked(systemConfigApi.getModelRegistry).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: { defaultModelKey: '', models: [] },
        timestamp: 1,
        traceId: 'trace-registry',
      },
    });
    vi.mocked(systemConfigApi.listPromptTemplates).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: [],
        timestamp: 1,
        traceId: 'trace-templates',
      },
    });
    vi.mocked(systemConfigApi.update).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: createSystemConfig('ai.knowledge.reasoning-mode.default', 'deep'),
        timestamp: 1,
        traceId: 'trace-update',
      },
    });

    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/config/system', component: SystemConfigView }],
    });
    await router.push('/config/system');

    const wrapper = mount(SystemConfigView, {
      global: {
        plugins: [router, ElementPlus],
      },
    });

    await flushPromises();

    await wrapper
      .get('[data-test="system-config-value-ai.knowledge.reasoning-mode.default"] .el-segmented__group label:nth-of-type(2) input')
      .setValue(true);
    await wrapper.get('[data-test="system-config-save-ai.knowledge.reasoning-mode.default"]').trigger('click');
    await flushPromises();

    expect(systemConfigApi.update).toHaveBeenCalledWith({
      configKey: 'ai.knowledge.reasoning-mode.default',
      configValue: 'deep',
      configType: 'string',
      description: 'ai.knowledge.reasoning-mode.default description',
    });
  }, 30_000);

  test('updates model registry with edited model cards', async () => {
    const { systemConfigApi } = await import('@/api/config');

    vi.mocked(systemConfigApi.getByKey).mockImplementation(async (configKey) => ({
      data: {
        code: 200,
        message: 'success',
        data: createSystemConfig(configKey, 'demo-value'),
        timestamp: 1,
        traceId: `trace-${configKey}`,
      },
    }));
    vi.mocked(systemConfigApi.getModelRegistry).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: {
          defaultModelKey: 'deepseek-chat',
          models: [
            {
              modelKey: 'deepseek-chat',
              displayName: 'DeepSeek Chat',
              providerType: 'openai-compatible',
              modelName: 'deepseek-chat',
              baseUrl: 'https://api.deepseek.com/v1',
              apiKey: null,
              apiKeyConfigured: true,
              apiKeyMasked: '已配置',
              enabled: true,
              isDefault: true,
              defaultTemperature: 1,
              maxTokens: 8192,
              temperatureSpecJson: '{"min":0,"max":2}',
              promptBindings: {
                deconstruct: 'deepseek-chat',
                structure: 'deepseek-chat',
                plot: 'deepseek-chat',
                theme: 'default',
              },
            },
            {
              modelKey: 'deepseek-reasoner',
              displayName: 'DeepSeek Reasoner',
              providerType: 'openai-compatible',
              protocol: 'responses',
              providerCapabilities: {
                schemaVersion: 1,
                supportsStreaming: true,
                supportsTools: true,
                supportsJsonObject: false,
                supportsReasoning: true,
                reportsUsage: true,
                reportsCacheUsage: true,
              },
              modelName: 'deepseek-reasoner',
              baseUrl: 'https://api.deepseek.com/v1',
              enabled: false,
              isDefault: false,
            },
          ],
        },
        timestamp: 1,
        traceId: 'trace-registry',
      },
    });
    vi.mocked(systemConfigApi.listPromptTemplates).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: [
          { id: 1, promptType: 'deconstruct', promptName: 'default', modelName: 'deepseek-chat' },
          { id: 2, promptType: 'structure', promptName: 'default', modelName: 'deepseek-chat' },
          { id: 3, promptType: 'plot', promptName: 'default', modelName: 'deepseek-chat' },
          { id: 4, promptType: 'theme', promptName: 'default', modelName: 'deepseek-chat' },
        ],
        timestamp: 1,
        traceId: 'trace-templates',
      },
    });
    vi.mocked(systemConfigApi.updateModelRegistry).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: {
          defaultModelKey: 'deepseek-chat',
          models: [
            {
              modelKey: 'deepseek-chat',
              displayName: 'DeepSeek Chat Updated',
              providerType: 'openai-compatible',
              protocol: 'responses',
              modelName: 'deepseek-chat',
              baseUrl: 'https://api.deepseek.com/v1',
              apiKey: null,
              apiKeyConfigured: true,
              apiKeyMasked: '已配置',
              enabled: true,
              isDefault: true,
              defaultTemperature: 0.8,
              maxTokens: 4096,
              temperatureSpecJson: '{"min":0,"max":2,"default":0.8}',
              promptBindings: {
                deconstruct: 'deepseek-chat',
                structure: 'deepseek-chat',
                plot: 'deepseek-chat',
                theme: 'default',
              },
            },
          ],
        },
        timestamp: 1,
        traceId: 'trace-registry-update',
      },
    });
    vi.mocked(systemConfigApi.probeModelProvider).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: {
          status: 'SUCCEEDED',
          profileKey: 'deepseek-chat',
          profileVersion: 'version-1',
          endpointFingerprint: 'endpoint-sha256',
          model: 'deepseek-chat',
          protocol: 'responses',
          latencyMillis: 32,
          usageReported: true,
          cacheUsageReported: false,
        },
        timestamp: 1,
        traceId: 'trace-provider-probe',
      },
    });

    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/config/system', component: SystemConfigView }],
    });
    await router.push('/config/system');

    const wrapper = mount(SystemConfigView, {
      global: {
        plugins: [router, ElementPlus],
      },
    });

    await flushPromises();

    expect(wrapper.get('[data-test="model-capabilities-status-deepseek-chat"]').text()).toContain('未验证');
    expect(wrapper.get('[data-test="model-capabilities-status-deepseek-reasoner"]').text()).toContain('已声明 v1');

    const protocolSelect = wrapper.getComponent('[data-test="model-protocol-deepseek-chat"]');
    expect(protocolSelect.props('modelValue')).toBe('unspecified');
    await protocolSelect.vm.$emit('update:modelValue', 'responses');
    await wrapper.get('[data-test="model-display-name-deepseek-chat"]').setValue('DeepSeek Chat Updated');
    await wrapper.get('[data-test="model-default-temperature-deepseek-chat"]').setValue('0.8');
    await wrapper.get('[data-test="model-max-tokens-deepseek-chat"]').setValue('4096');
    await wrapper.get('[data-test="model-temperature-spec-deepseek-chat"]').setValue('{"min":0,"max":2,"default":0.8}');
    await wrapper.get('[data-test="model-registry-save"]').trigger('click');
    await flushPromises();

    expect(systemConfigApi.updateModelRegistry).toHaveBeenCalledWith({
      defaultModelKey: 'deepseek-chat',
      providerRoutingPolicy: {
        schemaVersion: 1,
        enabled: false,
        orderedProfileKeys: [],
        // No candidates configured, so no failover hop is possible.
        maxFailovers: 0,
        cooldownSeconds: 60,
      },
      models: [
        {
          modelKey: 'deepseek-chat',
          displayName: 'DeepSeek Chat Updated',
          providerType: 'openai-compatible',
          protocol: 'responses',
          modelName: 'deepseek-chat',
          baseUrl: 'https://api.deepseek.com/v1',
          enabled: true,
          isDefault: true,
          defaultTemperature: 0.8,
          maxTokens: 4096,
          temperatureSpecJson: '{"min":0,"max":2,"default":0.8}',
          promptBindings: {
            deconstruct: 'deepseek-chat',
            structure: 'deepseek-chat',
            plot: 'deepseek-chat',
            theme: 'default',
          },
        },
        {
          modelKey: 'deepseek-reasoner',
          displayName: 'DeepSeek Reasoner',
          providerType: 'openai-compatible',
          protocol: 'responses',
          providerCapabilities: {
            schemaVersion: 1,
            supportsStreaming: true,
            supportsTools: true,
            supportsJsonObject: false,
            supportsReasoning: true,
            reportsUsage: true,
            reportsCacheUsage: true,
          },
          modelName: 'deepseek-reasoner',
          baseUrl: 'https://api.deepseek.com/v1',
          enabled: false,
          isDefault: false,
          promptBindings: {
            deconstruct: undefined,
            structure: undefined,
            plot: undefined,
            theme: undefined,
          },
        },
      ],
    });
    const payload = vi.mocked(systemConfigApi.updateModelRegistry).mock.calls[0]?.[0];
    expect(payload?.models[0]).not.toHaveProperty('apiKey');
    expect(payload?.models[0]).not.toHaveProperty('apiKeyMasked');
    expect(payload?.models[0]).not.toHaveProperty('apiKeyConfigured');
    expect(payload?.models[0]).not.toHaveProperty('providerCapabilities');

    await wrapper.get('[data-test="model-provider-probe-deepseek-chat"]').trigger('click');
    await flushPromises();

    expect(systemConfigApi.probeModelProvider).toHaveBeenCalledWith({ modelKey: 'deepseek-chat' });
    expect(wrapper.get('[data-test="model-provider-probe-status-deepseek-chat"]').text())
      .toContain('连接可用');

    await wrapper.get('[data-test="model-api-key-deepseek-chat"]').setValue('registry-key-updated');
    expect(wrapper.get('[data-test="model-provider-probe-deepseek-chat"]').attributes('disabled')).toBeDefined();
    expect(wrapper.find('[data-test="model-provider-probe-status-deepseek-chat"]').exists()).toBe(false);
    expect(wrapper.text()).toContain('配置已修改，保存后重新验证');
    await wrapper.get('[data-test="model-registry-save"]').trigger('click');
    await flushPromises();

    expect(systemConfigApi.updateModelRegistry).toHaveBeenCalledTimes(2);
    const explicitKeyPayload = vi.mocked(systemConfigApi.updateModelRegistry).mock.calls[1]?.[0];
    expect(explicitKeyPayload?.models[0]?.protocol).toBe('responses');
    expect(explicitKeyPayload?.models[0]?.apiKey).toBe('registry-key-updated');
    expect(explicitKeyPayload?.models[0]).not.toHaveProperty('apiKeyMasked');
    expect(explicitKeyPayload?.models[0]).not.toHaveProperty('apiKeyConfigured');
    expect(explicitKeyPayload?.models[0]).not.toHaveProperty('providerCapabilities');

    await wrapper.get('[data-test="model-capabilities-configure-deepseek-chat"]').trigger('click');
    await flushPromises();

    expect(wrapper.get('[data-test="model-capabilities-status-deepseek-chat"]').text()).toContain('已声明 v1');
    const streamingSwitch = wrapper.getComponent(
      '[data-test="model-capability-supportsStreaming-deepseek-chat"]',
    );
    expect(wrapper.getComponent('[data-test="model-prompt-cache-strategy-deepseek-chat"]').props('modelValue'))
      .toBe('deepseek_automatic');
    await streamingSwitch.vm.$emit('update:modelValue', true);
    await wrapper.get('[data-test="model-registry-save"]').trigger('click');
    await flushPromises();

    expect(systemConfigApi.updateModelRegistry).toHaveBeenCalledTimes(3);
    const capabilityPayload = vi.mocked(systemConfigApi.updateModelRegistry).mock.calls[2]?.[0];
    expect(capabilityPayload?.models[0]?.providerCapabilities).toEqual({
      schemaVersion: 1,
      supportsStreaming: true,
      supportsTools: false,
      supportsJsonObject: false,
      supportsReasoning: false,
      reportsUsage: false,
      reportsCacheUsage: false,
      promptCache: {
        strategy: 'deepseek_automatic',
        mode: 'provider_managed',
        retention: 'provider_default',
        breakpoint: 'none',
      },
    });

    type ProbeResponse = Awaited<ReturnType<typeof systemConfigApi.probeModelProvider>>;
    let resolveStaleProbe!: (value: ProbeResponse) => void;
    vi.mocked(systemConfigApi.probeModelProvider).mockImplementationOnce(
      () => new Promise<ProbeResponse>((resolve) => {
        resolveStaleProbe = resolve;
      }),
    );
    await wrapper.get('[data-test="model-provider-probe-deepseek-chat"]').trigger('click');
    expect(wrapper.get('[data-test="model-provider-probe-deepseek-chat"]').attributes('disabled')).toBeDefined();

    await wrapper.get('[data-test="model-registry-save"]').trigger('click');
    await flushPromises();
    resolveStaleProbe({
      data: {
        code: 200,
        message: 'success',
        data: {
          status: 'SUCCEEDED',
          profileKey: 'deepseek-chat',
          profileVersion: 'stale-version',
          endpointFingerprint: 'endpoint-sha256',
          model: 'deepseek-chat',
          protocol: 'responses',
          latencyMillis: 32,
          usageReported: true,
          cacheUsageReported: false,
        },
        timestamp: 1,
        traceId: 'trace-stale-provider-probe',
      },
    });
    await flushPromises();

    expect(wrapper.find('[data-test="model-provider-probe-status-deepseek-chat"]').exists()).toBe(false);

    const staleErrorSpy = vi.spyOn(ElMessage, 'error');
    let rejectStaleProbe!: (reason?: unknown) => void;
    vi.mocked(systemConfigApi.probeModelProvider).mockImplementationOnce(
      () => new Promise<ProbeResponse>((_resolve, reject) => {
        rejectStaleProbe = reject;
      }),
    );
    await wrapper.get('[data-test="model-provider-probe-deepseek-chat"]').trigger('click');
    await wrapper.get('[data-test="model-registry-save"]').trigger('click');
    await flushPromises();
    rejectStaleProbe(new Error('stale probe failure'));
    await flushPromises();

    expect(staleErrorSpy).not.toHaveBeenCalled();
    staleErrorSpy.mockRestore();
  }, 30_000);

  test('configures ordered provider routing and one bounded failover', async () => {
    const { systemConfigApi } = await import('@/api/config');
    const capabilities = {
      schemaVersion: 1 as const,
      supportsStreaming: true,
      supportsTools: true,
      supportsJsonObject: true,
      supportsReasoning: true,
      reportsUsage: true,
      reportsCacheUsage: true,
    };
    const models = [
      {
        modelKey: 'gateway-primary',
        displayName: 'Gateway Primary',
        providerType: 'openai-compatible',
        protocol: 'responses' as const,
        providerCapabilities: capabilities,
        modelName: 'upstream-primary',
        baseUrl: 'https://primary.example/v1',
        apiKeyConfigured: true,
        enabled: true,
        isDefault: true,
      },
      {
        modelKey: 'gateway-standby',
        displayName: 'Gateway Standby',
        providerType: 'openai-compatible',
        protocol: 'responses' as const,
        providerCapabilities: capabilities,
        modelName: 'upstream-standby',
        baseUrl: 'https://standby.example/v1',
        apiKeyConfigured: true,
        enabled: true,
        isDefault: false,
      },
    ];
    const initialPolicy = {
      schemaVersion: 1 as const,
      enabled: true,
      orderedProfileKeys: ['gateway-standby', 'gateway-primary'],
      maxFailovers: 1,
      cooldownSeconds: 120,
    };

    vi.mocked(systemConfigApi.listKnown).mockResolvedValue({
      data: { code: 200, message: 'success', data: [], timestamp: 1, traceId: 'trace-known' },
    });
    vi.mocked(systemConfigApi.getByKey).mockImplementation(async (configKey) => ({
      data: {
        code: 200,
        message: 'success',
        data: createSystemConfig(configKey, 'demo-value'),
        timestamp: 1,
        traceId: `trace-${configKey}`,
      },
    }));
    vi.mocked(systemConfigApi.listPromptTemplates).mockResolvedValue({
      data: { code: 200, message: 'success', data: [], timestamp: 1, traceId: 'trace-templates' },
    });
    vi.mocked(systemConfigApi.getModelRegistry).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: { defaultModelKey: 'gateway-primary', models, providerRoutingPolicy: initialPolicy },
        timestamp: 1,
        traceId: 'trace-registry',
      },
    });
    vi.mocked(systemConfigApi.updateModelRegistry).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: {
          defaultModelKey: 'gateway-primary',
          models,
          providerRoutingPolicy: {
            ...initialPolicy,
            orderedProfileKeys: ['gateway-primary', 'gateway-standby'],
            cooldownSeconds: 180,
          },
        },
        timestamp: 1,
        traceId: 'trace-registry-update',
      },
    });

    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/config/system', component: SystemConfigView }],
    });
    await router.push('/config/system');
    const wrapper = mount(SystemConfigView, {
      global: { plugins: [router, ElementPlus] },
    });
    await flushPromises();

    expect(wrapper.get('[data-test="provider-routing-status"]').text()).toContain('已开启');
    const routeRows = wrapper.findAll('.system-config-page__provider-routing-row');
    expect(routeRows.map((row) => row.attributes('data-test'))).toEqual([
      'provider-routing-row-gateway-standby',
      'provider-routing-row-gateway-primary',
    ]);

    await wrapper.getComponent('[data-test="provider-routing-max-failovers"]')
      .vm.$emit('update:modelValue', 0);
    await wrapper.vm.$nextTick();
    expect(wrapper.get('[data-test="provider-routing-status"]').text()).toContain('已配置，未切换');
    expect(wrapper.text()).toContain('运行时继续使用单 Profile 路径');
    await wrapper.getComponent('[data-test="provider-routing-max-failovers"]')
      .vm.$emit('update:modelValue', 1);
    await wrapper.get('[data-test="provider-routing-up-gateway-primary"]').trigger('click');
    await wrapper.getComponent('[data-test="provider-routing-cooldown-seconds"]')
      .vm.$emit('update:modelValue', 180);
    await wrapper.get('[data-test="model-registry-save"]').trigger('click');
    await flushPromises();

    const payload = vi.mocked(systemConfigApi.updateModelRegistry).mock.calls[0]?.[0];
    expect(payload?.providerRoutingPolicy).toEqual({
      schemaVersion: 1,
      enabled: true,
      orderedProfileKeys: ['gateway-primary', 'gateway-standby'],
      maxFailovers: 1,
      cooldownSeconds: 180,
    });
    expect(payload?.models.map((model) => model.modelKey)).toEqual([
      'gateway-primary',
      'gateway-standby',
    ]);
  }, 30_000);

  test('allows max failovers up to the routing candidate count', async () => {
    const { systemConfigApi } = await import('@/api/config');
    const capabilities = {
      schemaVersion: 1 as const,
      supportsStreaming: true,
      supportsTools: true,
      supportsJsonObject: true,
      supportsReasoning: true,
      reportsUsage: true,
      reportsCacheUsage: true,
    };
    const models = ['gateway-primary', 'gateway-standby', 'gateway-third'].map((modelKey, index) => ({
      modelKey,
      displayName: modelKey,
      providerType: 'openai-compatible',
      protocol: 'responses' as const,
      providerCapabilities: capabilities,
      modelName: `upstream-${index}`,
      baseUrl: `https://${modelKey}.example/v1`,
      apiKeyConfigured: true,
      enabled: true,
      isDefault: index === 0,
    }));

    vi.mocked(systemConfigApi.listKnown).mockResolvedValue({
      data: { code: 200, message: 'success', data: [], timestamp: 1, traceId: 'trace-known' },
    });
    vi.mocked(systemConfigApi.getByKey).mockImplementation(async (configKey) => ({
      data: {
        code: 200,
        message: 'success',
        data: createSystemConfig(configKey, 'demo-value'),
        timestamp: 1,
        traceId: 'trace-config',
      },
    }));
    vi.mocked(systemConfigApi.listPromptTemplates).mockResolvedValue({
      data: { code: 200, message: 'success', data: [], timestamp: 1, traceId: 'trace-prompts' },
    });
    vi.mocked(systemConfigApi.getModelRegistry).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: {
          defaultModelKey: 'gateway-primary',
          models,
          providerRoutingPolicy: {
            schemaVersion: 1 as const,
            enabled: true,
            orderedProfileKeys: ['gateway-primary', 'gateway-standby', 'gateway-third'],
            maxFailovers: 2,
            cooldownSeconds: 60,
          },
        },
        timestamp: 1,
        traceId: 'trace-registry',
      },
    });
    vi.mocked(systemConfigApi.updateModelRegistry).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: {
          defaultModelKey: 'gateway-primary',
          models,
          providerRoutingPolicy: {
            schemaVersion: 1 as const,
            enabled: true,
            orderedProfileKeys: ['gateway-primary', 'gateway-standby', 'gateway-third'],
            maxFailovers: 3,
            cooldownSeconds: 60,
          },
        },
        timestamp: 1,
        traceId: 'trace-registry-update',
      },
    });

    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/config/system', component: SystemConfigView }],
    });
    await router.push('/config/system');
    const wrapper = mount(SystemConfigView, {
      global: { plugins: [router, ElementPlus] },
    });
    await flushPromises();

    const input = wrapper.getComponent('[data-test="provider-routing-max-failovers"]');
    expect(input.props('max')).toBe(3);
    expect(input.props('modelValue')).toBe(2);

    await input.vm.$emit('update:modelValue', 3);
    await wrapper.get('[data-test="model-registry-save"]').trigger('click');
    await flushPromises();

    const payload = vi.mocked(systemConfigApi.updateModelRegistry).mock.calls[0]?.[0];
    expect(payload?.providerRoutingPolicy?.maxFailovers).toBe(3);
  }, 30_000);

  test('clamps max failovers when a routing candidate is removed', async () => {
    const { systemConfigApi } = await import('@/api/config');
    const capabilities = {
      schemaVersion: 1 as const,
      supportsStreaming: true,
      supportsTools: true,
      supportsJsonObject: true,
      supportsReasoning: true,
      reportsUsage: true,
      reportsCacheUsage: true,
    };
    const models = ['gateway-primary', 'gateway-standby', 'gateway-third'].map((modelKey, index) => ({
      modelKey,
      displayName: modelKey,
      providerType: 'openai-compatible',
      protocol: 'responses' as const,
      providerCapabilities: capabilities,
      modelName: `upstream-${index}`,
      baseUrl: `https://${modelKey}.example/v1`,
      apiKeyConfigured: true,
      enabled: true,
      isDefault: index === 0,
    }));

    vi.mocked(systemConfigApi.listKnown).mockResolvedValue({
      data: { code: 200, message: 'success', data: [], timestamp: 1, traceId: 'trace-known' },
    });
    vi.mocked(systemConfigApi.getByKey).mockImplementation(async (configKey) => ({
      data: {
        code: 200,
        message: 'success',
        data: createSystemConfig(configKey, 'demo-value'),
        timestamp: 1,
        traceId: 'trace-config',
      },
    }));
    vi.mocked(systemConfigApi.listPromptTemplates).mockResolvedValue({
      data: { code: 200, message: 'success', data: [], timestamp: 1, traceId: 'trace-prompts' },
    });
    vi.mocked(systemConfigApi.getModelRegistry).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: {
          defaultModelKey: 'gateway-primary',
          models,
          providerRoutingPolicy: {
            schemaVersion: 1 as const,
            enabled: true,
            orderedProfileKeys: ['gateway-primary', 'gateway-standby', 'gateway-third'],
            maxFailovers: 3,
            cooldownSeconds: 60,
          },
        },
        timestamp: 1,
        traceId: 'trace-registry',
      },
    });

    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/config/system', component: SystemConfigView }],
    });
    await router.push('/config/system');
    const wrapper = mount(SystemConfigView, {
      global: { plugins: [router, ElementPlus] },
    });
    await flushPromises();

    expect(wrapper.getComponent('[data-test="provider-routing-max-failovers"]').props('modelValue')).toBe(3);

    await wrapper.get('[data-test="provider-routing-remove-gateway-third"]').trigger('click');
    await wrapper.vm.$nextTick();

    const input = wrapper.getComponent('[data-test="provider-routing-max-failovers"]');
    expect(input.props('max')).toBe(2);
    expect(input.props('modelValue')).toBe(2);
  }, 30_000);
});
