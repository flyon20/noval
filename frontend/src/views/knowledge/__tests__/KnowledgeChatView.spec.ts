import ElementPlus from 'element-plus';
import { flushPromises, mount } from '@vue/test-utils';
import KnowledgeChatView from '../KnowledgeChatView.vue';
import { knowledgeApi } from '@/api/knowledge';
import type { KnowledgeChatResponse } from '@/types/knowledge';

vi.mock('@/api/knowledge', () => ({
  knowledgeApi: {
    streamChat: vi.fn(),
  },
}));

const finalResponse: KnowledgeChatResponse = {
  status: 'answered',
  answer: '第一段 第二段[1]',
  candidates: [],
  sources: [
    {
      chunkId: 1,
      bookName: '测试书',
      chapterNo: 1,
      title: '第一章',
      preview: '来源摘要',
      score: 0.9,
    },
  ],
  actions: [],
  resultJson: {},
};

function mountView() {
  return mount(KnowledgeChatView, {
    global: {
      plugins: [ElementPlus],
    },
  });
}

describe('KnowledgeChatView', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    window.localStorage.clear();
  });

  test('renders streaming deltas before final response resolves', async () => {
    let callbacks: any;
    let resolveResult!: (response: KnowledgeChatResponse) => void;
    vi.mocked(knowledgeApi.streamChat).mockImplementation((_payload, streamCallbacks) => {
      callbacks = streamCallbacks;
      return {
        abort: vi.fn(),
        result: new Promise<KnowledgeChatResponse>((resolve) => {
          resolveResult = resolve;
        }),
      } as never;
    });

    const wrapper = mountView();

    await wrapper.find('[data-test="knowledge-question-input"] textarea').setValue('测试问题');
    await wrapper.find('[data-test="knowledge-send-button"]').trigger('click');
    callbacks.onDelta({ event: 'delta', delta: '第一段 ' });
    await flushPromises();

    expect(wrapper.text()).toContain('测试问题');
    expect(wrapper.text()).toContain('第一段');
    expect(wrapper.text()).not.toContain('第二段');

    callbacks.onDelta({ event: 'delta', delta: '第二段[1]' });
    callbacks.onDone({ event: 'done', data: finalResponse });
    resolveResult(finalResponse);
    await flushPromises();

    expect(wrapper.text()).toContain('第一段 第二段[1]');
    expect(wrapper.text()).toContain('引用来源 1');
  });

  test('restores short term chat memory after remounting the page', async () => {
    window.localStorage.setItem('noval:knowledge-chat:draft:v1', JSON.stringify({
      messages: [
        { role: 'user', content: '上一轮问题' },
        { role: 'assistant', content: '上一轮回答[1]', status: 'answered', sources: [] },
      ],
      contextSummary: '上一轮摘要',
      chapterCount: 5,
      status: 'answered',
      answer: '上一轮回答[1]',
    }));

    const wrapper = mountView();

    expect(wrapper.text()).toContain('上一轮问题');
    expect(wrapper.text()).toContain('上一轮回答[1]');
  });
});
