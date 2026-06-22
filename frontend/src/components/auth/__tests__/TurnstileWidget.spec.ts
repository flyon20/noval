import { flushPromises, mount } from '@vue/test-utils';
import TurnstileWidget from '../TurnstileWidget.vue';

type TestTurnstileApi = {
  render: ReturnType<typeof vi.fn>;
  reset: ReturnType<typeof vi.fn>;
  remove: ReturnType<typeof vi.fn>;
};

function setTurnstileApi(api: TestTurnstileApi) {
  (window as unknown as { turnstile?: TestTurnstileApi }).turnstile = api;
}

describe('TurnstileWidget', () => {
  beforeEach(() => {
    delete (window as unknown as { turnstile?: TestTurnstileApi }).turnstile;
    delete (window as unknown as { __novalTurnstileScriptPromise__?: Promise<void> }).__novalTurnstileScriptPromise__;
  });

  test('renders an explicit visible localized widget with recovery callbacks', async () => {
    const render = vi.fn().mockReturnValue('widget-1');
    setTurnstileApi({
      render,
      reset: vi.fn(),
      remove: vi.fn(),
    });

    const wrapper = mount(TurnstileWidget, {
      props: {
        siteKey: 'site-key',
      },
    });

    await flushPromises();

    expect(render).toHaveBeenCalledTimes(1);
    const options = render.mock.calls[0][1] as Record<string, unknown>;
    expect(options).toMatchObject({
      sitekey: 'site-key',
      appearance: 'always',
      execution: 'render',
      language: 'zh-CN',
      size: 'flexible',
      retry: 'auto',
      'refresh-expired': 'auto',
      'refresh-timeout': 'auto',
    });

    (options['timeout-callback'] as () => void)();
    (options['unsupported-callback'] as () => void)();

    expect(wrapper.emitted('timeout')).toHaveLength(1);
    expect(wrapper.emitted('unsupported')).toHaveLength(1);
  });
});
