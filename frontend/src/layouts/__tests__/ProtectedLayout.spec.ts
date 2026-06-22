import ElementPlus from 'element-plus';
import { flushPromises, mount } from '@vue/test-utils';
import { createMemoryHistory, createRouter } from 'vue-router';
import ProtectedLayout from '../ProtectedLayout.vue';
import { authApi } from '@/api/auth';

vi.mock('@/api/auth', () => ({
  authApi: {
    changePassword: vi.fn(),
    sendSmsCode: vi.fn(),
  },
}));

vi.mock('@/api/system', () => ({
  systemApi: {
    getAuthPublicConfig: vi.fn().mockResolvedValue({
      data: {
        data: {
          turnstileEnabled: true,
          turnstileSiteKey: 'site-key',
        },
      },
    }),
  },
}));

vi.mock('@/stores/auth', () => ({
  useAuthStore: () => ({
    session: {
      userId: 7,
      username: 'alice',
      phone: '13800138000',
      roles: ['USER'],
    },
    logout: vi.fn(),
  }),
}));

describe('ProtectedLayout password change', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  async function mountLayout() {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/', component: { template: '<div data-test="page">page</div>' } }],
    });
    await router.push('/');

    const wrapper = mount(ProtectedLayout, {
      global: {
        plugins: [router, ElementPlus],
        stubs: {
          AppShell: {
            template: `
              <section>
                <button data-test="open-password-dialog" @click="$emit('change-password')">open</button>
                <slot />
              </section>
            `,
          },
          RouterView: {
            template: '<div />',
          },
          TurnstileWidget: {
            name: 'TurnstileWidget',
            props: ['siteKey'],
            emits: ['verified', 'expired', 'error', 'timeout', 'unsupported'],
            template: '<div data-test="turnstile" />',
          },
        },
      },
    });

    await flushPromises();
    return wrapper;
  }

  test('sends reset-password sms with turnstile token and changes password by sms code', async () => {
    vi.mocked(authApi.sendSmsCode).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: {
          smsOutId: 'out-id-001',
          debugVerifyCode: null,
        },
        timestamp: 1,
        traceId: 'trace-sms',
      },
    });
    vi.mocked(authApi.changePassword).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: null,
        timestamp: 1,
        traceId: 'trace-change',
      },
    });

    const wrapper = await mountLayout();

    await wrapper.get('[data-test="open-password-dialog"]').trigger('click');
    await flushPromises();
    await wrapper.findComponent({ name: 'ElRadioGroup' }).vm.$emit('update:modelValue', 'SMS_CODE');
    await flushPromises();
    await wrapper.getComponent({ name: 'TurnstileWidget' }).vm.$emit('verified', 'turnstile-token');
    await wrapper.get('[data-test="password-send-sms"]').trigger('click');
    await flushPromises();

    expect(authApi.sendSmsCode).toHaveBeenCalledWith({
      phone: '13800138000',
      bizType: 'RESET_PASSWORD',
      turnstileToken: 'turnstile-token',
    });

    await wrapper.get('[data-test="password-sms-code"]').setValue('123456');
    await wrapper.get('[data-test="password-new"]').setValue('NewPassword123');
    await wrapper.get('[data-test="password-confirm"]').setValue('NewPassword123');
    await wrapper.get('[data-test="password-submit"]').trigger('click');
    await flushPromises();

    expect(authApi.changePassword).toHaveBeenCalledWith({
      verifyMode: 'SMS_CODE',
      smsCode: '123456',
      smsOutId: 'out-id-001',
      newPassword: 'NewPassword123',
    });
  });

  test('does not send password sms when turnstile is unavailable', async () => {
    const wrapper = await mountLayout();

    await wrapper.get('[data-test="open-password-dialog"]').trigger('click');
    await flushPromises();
    await wrapper.findComponent({ name: 'ElRadioGroup' }).vm.$emit('update:modelValue', 'SMS_CODE');
    await flushPromises();
    await wrapper.getComponent({ name: 'TurnstileWidget' }).vm.$emit('timeout');
    await wrapper.get('[data-test="password-send-sms"]').trigger('click');
    await flushPromises();

    expect(authApi.sendSmsCode).not.toHaveBeenCalled();
  });
});
