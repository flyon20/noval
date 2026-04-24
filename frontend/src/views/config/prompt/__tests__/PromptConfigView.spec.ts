import ElementPlus from 'element-plus';
import { flushPromises, mount } from '@vue/test-utils';
import { createMemoryHistory, createRouter } from 'vue-router';
import PromptConfigView from '../PromptConfigView.vue';

vi.mock('@/api/config', () => ({
  promptConfigApi: {
    getByType: vi.fn(),
    listTemplates: vi.fn(),
    update: vi.fn(),
    remove: vi.fn(),
  },
  systemConfigApi: {
    getModelOptions: vi.fn(),
  },
}));

function createPromptConfig(promptType: 'deconstruct' | 'structure' | 'plot' | 'theme') {
  return {
    id: 1,
    promptType,
    promptName: `${promptType}-default`,
    promptContent: `这是 ${promptType} 的提示词，正文占位：{{content}}`,
    modelName: 'dify',
    temperature: 0.7,
    maxTokens: 2048,
    inputJsonSchema: '{"type":"object","properties":{"content":{"type":"string"}}}',
    inputExampleJson: '{"content":"example-input"}',
    outputJsonSchema: '{"type":"object"}',
    outputExampleJson: '{"summary":"example"}',
    postProcessType: 'json_extract',
    parseConfigJson: '{"parser":"json"}',
  };
}

describe('PromptConfigView', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  test('loads default prompt config and switches prompt type', async () => {
    const { promptConfigApi, systemConfigApi } = await import('@/api/config');

    vi.mocked(promptConfigApi.getByType).mockImplementation(async (promptType) => ({
      data: {
        code: 200,
        message: 'success',
        data: createPromptConfig(promptType),
        timestamp: 1,
        traceId: `trace-${promptType}`,
      },
    }));
    vi.mocked(promptConfigApi.listTemplates).mockImplementation(async (promptType) => ({
      data: {
        code: 200,
        message: 'success',
        data: [
          { id: 1, promptType, promptName: 'default', modelName: 'deepseek-chat' },
          { id: 2, promptType, promptName: 'kimi-k2.5', modelName: 'kimi-k2.5' },
        ],
        timestamp: 1,
        traceId: `trace-templates-${promptType}`,
      },
    }));
    vi.mocked(systemConfigApi.getModelOptions).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: [
          { modelKey: 'dify', displayName: 'Dify', providerType: 'workflow' },
          { modelKey: 'deepseek-chat', displayName: 'DeepSeek Chat', providerType: 'openai-compatible' },
        ],
        timestamp: 1,
        traceId: 'trace-models',
      },
    });

    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/config/prompt', component: PromptConfigView }],
    });
    await router.push('/config/prompt');

    const wrapper = mount(PromptConfigView, {
      global: {
        plugins: [router, ElementPlus],
      },
    });

    await flushPromises();

    expect(promptConfigApi.getByType).toHaveBeenCalledWith('deconstruct', undefined);
    expect(promptConfigApi.listTemplates).toHaveBeenCalledWith('deconstruct');
    expect(wrapper.text()).toContain('{{content}}');
    expect(wrapper.get('[data-test="prompt-contract-status"]').text()).toContain('系统预置结构约束已加载');
    expect((wrapper.get('[data-test="prompt-post-process-type-input"]').element as HTMLInputElement).value).toBe('json_extract');
    expect((wrapper.get('[data-test="prompt-input-json-schema-input"]').element as HTMLTextAreaElement).value).toContain('"content"');

    await wrapper.get('[data-test="prompt-type-structure"]').trigger('click');
    await flushPromises();

    expect(promptConfigApi.getByType).toHaveBeenLastCalledWith('structure', undefined);
  });

  test('switches prompt template by template dropdown', async () => {
    const { promptConfigApi, systemConfigApi } = await import('@/api/config');

    vi.mocked(promptConfigApi.listTemplates).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: [
          { id: 1, promptType: 'deconstruct', promptName: 'default', modelName: 'deepseek-chat' },
          { id: 2, promptType: 'deconstruct', promptName: 'kimi-k2.5', modelName: 'kimi-k2.5' },
        ],
        timestamp: 1,
        traceId: 'trace-templates',
      },
    });
    vi.mocked(promptConfigApi.getByType)
      .mockResolvedValueOnce({
        data: {
          code: 200,
          message: 'success',
          data: createPromptConfig('deconstruct'),
          timestamp: 1,
          traceId: 'trace-default',
        },
      } as never)
      .mockResolvedValueOnce({
        data: {
          code: 200,
          message: 'success',
          data: {
            ...createPromptConfig('deconstruct'),
            id: 2,
            promptName: 'kimi-k2.5',
            modelName: 'kimi-k2.5',
            promptContent: 'Kimi 专属模板 {{content}}',
          },
          timestamp: 1,
          traceId: 'trace-kimi',
        },
      } as never);
    vi.mocked(systemConfigApi.getModelOptions).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: [
          { modelKey: 'deepseek-chat', displayName: 'DeepSeek Chat', providerType: 'openai-compatible' },
          { modelKey: 'kimi-k2.5', displayName: 'kimi', providerType: 'openai-compatible' },
        ],
        timestamp: 1,
        traceId: 'trace-models',
      },
    });

    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/config/prompt', component: PromptConfigView }],
    });
    await router.push('/config/prompt');

    const wrapper = mount(PromptConfigView, {
      global: {
        plugins: [router, ElementPlus],
      },
    });

    await flushPromises();

    const select = wrapper.findComponent({ name: 'ElSelect' });
    select.vm.$emit('update:modelValue', 'kimi-k2.5');
    await (wrapper.vm as unknown as { handleTemplateChange: (name: string) => Promise<void> }).handleTemplateChange('kimi-k2.5');
    await flushPromises();

    expect(promptConfigApi.getByType).toHaveBeenLastCalledWith('deconstruct', 'kimi-k2.5');
    expect((wrapper.get('[data-test="prompt-name-input"]').element as HTMLInputElement).value).toBe('kimi-k2.5');
    expect((wrapper.get('[data-test="prompt-content-input"]').element as HTMLTextAreaElement).value).toContain('Kimi 专属模板');
  });

  test('shows backend error message and trace id when prompt config loading fails', async () => {
    const { promptConfigApi, systemConfigApi } = await import('@/api/config');

    vi.mocked(promptConfigApi.listTemplates).mockRejectedValue({
      response: {
        data: {
          code: 500,
          message: 'prompt config not found',
          traceId: 'trace-load-failed',
        },
      },
    });
    vi.mocked(promptConfigApi.getByType).mockRejectedValue({
      response: {
        data: {
          code: 500,
          message: 'prompt config not found',
          traceId: 'trace-load-failed',
        },
      },
    });
    vi.mocked(systemConfigApi.getModelOptions).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: [],
        timestamp: 1,
        traceId: 'trace-models',
      },
    });

    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/config/prompt', component: PromptConfigView }],
    });
    await router.push('/config/prompt');

    const wrapper = mount(PromptConfigView, {
      global: {
        plugins: [router, ElementPlus],
      },
    });

    await flushPromises();

    expect(wrapper.text()).toContain('提示词配置加载失败');
    expect(wrapper.text()).toContain('prompt config not found');
    expect(wrapper.text()).toContain('trace-load-failed');
  });

  test('submits prompt config update with current form values', async () => {
    const { promptConfigApi, systemConfigApi } = await import('@/api/config');

    vi.mocked(promptConfigApi.listTemplates).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: [
          { id: 1, promptType: 'deconstruct', promptName: 'default', modelName: 'dify' },
        ],
        timestamp: 1,
        traceId: 'trace-templates',
      },
    });
    vi.mocked(promptConfigApi.getByType).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: createPromptConfig('deconstruct'),
        timestamp: 1,
        traceId: 'trace-deconstruct',
      },
    });
    vi.mocked(systemConfigApi.getModelOptions).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: [
          { modelKey: 'dify', displayName: 'Dify', providerType: 'workflow' },
          { modelKey: 'dify-chat', displayName: 'Dify Chat', providerType: 'workflow' },
        ],
        timestamp: 1,
        traceId: 'trace-models',
      },
    });
    vi.mocked(promptConfigApi.update).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: createPromptConfig('deconstruct'),
        timestamp: 1,
        traceId: 'trace-update',
      },
    });

    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/config/prompt', component: PromptConfigView }],
    });
    await router.push('/config/prompt');

    const wrapper = mount(PromptConfigView, {
      global: {
        plugins: [router, ElementPlus],
      },
    });

    await flushPromises();

    await wrapper.get('[data-test="prompt-name-input"]').setValue('deconstruct-updated');
    await wrapper
      .get('[data-test="prompt-content-input"]')
      .setValue('新的提示词模板，保留正文占位：{{content}}');
    wrapper.findAllComponents({ name: 'ElSelect' })[1].vm.$emit('update:modelValue', 'dify-chat');
    await flushPromises();
    await wrapper.get('[data-test="prompt-temperature-input"]').setValue('0.9');
    await wrapper.get('[data-test="prompt-max-tokens-input"]').setValue('4096');
    await wrapper.get('[data-test="prompt-contract-unlock"]').trigger('click');
    await flushPromises();
    await wrapper.get('[data-test="prompt-input-json-schema-input"]').setValue('{"type":"object","properties":{"content":{"type":"string"}}}');
    await wrapper.get('[data-test="prompt-input-example-json-input"]').setValue('{"content":"example-input"}');
    await wrapper.get('[data-test="prompt-output-json-schema-input"]').setValue('{"type":"object","properties":{"summary":{"type":"string"}}}');
    await wrapper.get('[data-test="prompt-output-example-json-input"]').setValue('{"summary":"example"}');
    await wrapper.get('[data-test="prompt-post-process-type-input"]').setValue('json_extract');
    await wrapper.get('[data-test="prompt-parse-config-json-input"]').setValue('{"parser":"json","trimMarkdownFence":true}');
    await wrapper.get('[data-test="prompt-save-button"]').trigger('click');
    await flushPromises();

    expect(promptConfigApi.update).toHaveBeenCalledWith({
      promptType: 'deconstruct',
      promptName: 'deconstruct-updated',
      promptContent: '新的提示词模板，保留正文占位：{{content}}',
      modelName: 'dify-chat',
      temperature: 0.9,
      maxTokens: 4096,
      inputJsonSchema: '{"type":"object","properties":{"content":{"type":"string"}}}',
      inputExampleJson: '{"content":"example-input"}',
      outputJsonSchema: '{"type":"object","properties":{"summary":{"type":"string"}}}',
      outputExampleJson: '{"summary":"example"}',
      postProcessType: 'json_extract',
      parseConfigJson: '{"parser":"json","trimMarkdownFence":true}',
    });
  });

  test('saving a newly typed template name uses the new promptName', async () => {
    const { promptConfigApi, systemConfigApi } = await import('@/api/config');

    vi.mocked(promptConfigApi.listTemplates).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: [
          { id: 1, promptType: 'deconstruct', promptName: 'default', modelName: 'deepseek-chat' },
        ],
        timestamp: 1,
        traceId: 'trace-templates',
      },
    });
    vi.mocked(promptConfigApi.getByType).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: createPromptConfig('deconstruct'),
        timestamp: 1,
        traceId: 'trace-default',
      },
    } as never);
    vi.mocked(systemConfigApi.getModelOptions).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: [
          { modelKey: 'kimi-k2.5', displayName: 'kimi', providerType: 'openai-compatible' },
        ],
        timestamp: 1,
        traceId: 'trace-models',
      },
    });
    vi.mocked(promptConfigApi.update).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: {
          ...createPromptConfig('deconstruct'),
          promptName: 'kimi-k2.5',
          modelName: 'kimi-k2.5',
        },
        timestamp: 1,
        traceId: 'trace-update',
      },
    } as never);

    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/config/prompt', component: PromptConfigView }],
    });
    await router.push('/config/prompt');

    const wrapper = mount(PromptConfigView, {
      global: {
        plugins: [router, ElementPlus],
      },
    });

    await flushPromises();

    await (wrapper.vm as unknown as { handleTemplateChange: (name: string) => Promise<void> }).handleTemplateChange('kimi-k2.5');
    await wrapper.get('[data-test="prompt-content-input"]').setValue('Kimi 专属模板 {{content}}');
    wrapper.findAllComponents({ name: 'ElSelect' })[1].vm.$emit('update:modelValue', 'kimi-k2.5');
    await flushPromises();
    await wrapper.get('[data-test="prompt-save-button"]').trigger('click');
    await flushPromises();

    expect(promptConfigApi.update).toHaveBeenCalledWith(expect.objectContaining({
      promptType: 'deconstruct',
      promptName: 'kimi-k2.5',
      modelName: 'kimi-k2.5',
    }));
  });

  test('default template locks template name input and hides delete action', async () => {
    const { promptConfigApi, systemConfigApi } = await import('@/api/config');

    vi.mocked(promptConfigApi.listTemplates).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: [
          { id: 1, promptType: 'deconstruct', promptName: 'default', modelName: 'deepseek-chat', isDefault: true },
        ],
        timestamp: 1,
        traceId: 'trace-templates',
      },
    });
    vi.mocked(promptConfigApi.getByType).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: {
          ...createPromptConfig('deconstruct'),
          promptName: 'default',
        },
        timestamp: 1,
        traceId: 'trace-default',
      },
    });
    vi.mocked(systemConfigApi.getModelOptions).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: [],
        timestamp: 1,
        traceId: 'trace-models',
      },
    });

    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/config/prompt', component: PromptConfigView }],
    });
    await router.push('/config/prompt');

    const wrapper = mount(PromptConfigView, {
      global: {
        plugins: [router, ElementPlus],
      },
    });

    await flushPromises();

    expect(wrapper.find('[data-test="prompt-default-template-hint"]').exists()).toBe(true);
    expect(wrapper.find('[data-test="prompt-delete-button"]').exists()).toBe(false);
  });

  test('legacy named default template is also protected by isDefault flag', async () => {
    const { promptConfigApi, systemConfigApi } = await import('@/api/config');

    vi.mocked(promptConfigApi.listTemplates).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: [
          { id: 1, promptType: 'deconstruct', promptName: 'default-deconstruct', modelName: 'deepseek-chat', isDefault: true },
        ],
        timestamp: 1,
        traceId: 'trace-templates',
      },
    });
    vi.mocked(promptConfigApi.getByType).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: {
          ...createPromptConfig('deconstruct'),
          promptName: 'default-deconstruct',
          isDefault: true,
        },
        timestamp: 1,
        traceId: 'trace-default-legacy',
      },
    } as never);
    vi.mocked(systemConfigApi.getModelOptions).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: [],
        timestamp: 1,
        traceId: 'trace-models',
      },
    });

    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/config/prompt', component: PromptConfigView }],
    });
    await router.push('/config/prompt');

    const wrapper = mount(PromptConfigView, {
      global: {
        plugins: [router, ElementPlus],
      },
    });

    await flushPromises();

    expect(wrapper.find('[data-test="prompt-default-template-hint"]').exists()).toBe(true);
    expect(wrapper.find('[data-test="prompt-delete-button"]').exists()).toBe(false);
  });

  test('non-default template shows delete action', async () => {
    const { promptConfigApi, systemConfigApi } = await import('@/api/config');

    vi.mocked(promptConfigApi.listTemplates).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: [
          { id: 1, promptType: 'deconstruct', promptName: 'default', modelName: 'deepseek-chat', isDefault: true },
          { id: 2, promptType: 'deconstruct', promptName: 'kimi-template', modelName: 'kimi-k2.5', isDefault: false },
        ],
        timestamp: 1,
        traceId: 'trace-templates',
      },
    });
    vi.mocked(promptConfigApi.getByType).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: {
          ...createPromptConfig('deconstruct'),
          id: 2,
          promptName: 'kimi-template',
          modelName: 'kimi-k2.5',
        },
        timestamp: 1,
        traceId: 'trace-kimi-template',
      },
    });
    vi.mocked(systemConfigApi.getModelOptions).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: [],
        timestamp: 1,
        traceId: 'trace-models',
      },
    });

    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/config/prompt', component: PromptConfigView }],
    });
    await router.push('/config/prompt');

    const wrapper = mount(PromptConfigView, {
      global: {
        plugins: [router, ElementPlus],
      },
    });

    await flushPromises();

    expect(wrapper.get('[data-test="prompt-name-input"]').attributes('disabled')).toBeUndefined();
    expect(wrapper.find('[data-test="prompt-delete-button"]').exists()).toBe(true);
  });
});
