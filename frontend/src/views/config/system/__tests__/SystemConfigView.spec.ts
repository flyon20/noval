import ElementPlus from 'element-plus';
import { flushPromises, mount } from '@vue/test-utils';
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
  });

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
  });

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
  });

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
  });

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

    await wrapper.get('[data-test="model-display-name-deepseek-chat"]').setValue('DeepSeek Chat Updated');
    await wrapper.get('[data-test="model-api-key-deepseek-chat"]').setValue('registry-key-updated');
    await wrapper.get('[data-test="model-default-temperature-deepseek-chat"]').setValue('0.8');
    await wrapper.get('[data-test="model-max-tokens-deepseek-chat"]').setValue('4096');
    await wrapper.get('[data-test="model-temperature-spec-deepseek-chat"]').setValue('{"min":0,"max":2,"default":0.8}');
    await wrapper.get('[data-test="model-registry-save"]').trigger('click');
    await flushPromises();

    expect(systemConfigApi.updateModelRegistry).toHaveBeenCalledWith({
      defaultModelKey: 'deepseek-chat',
      models: [
        {
          modelKey: 'deepseek-chat',
          displayName: 'DeepSeek Chat Updated',
          providerType: 'openai-compatible',
          modelName: 'deepseek-chat',
          baseUrl: 'https://api.deepseek.com/v1',
          apiKey: 'registry-key-updated',
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
    });
  });
});
