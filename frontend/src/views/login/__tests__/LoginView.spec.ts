import ElementPlus from 'element-plus';
import { createPinia, setActivePinia } from 'pinia';
import { flushPromises, mount } from '@vue/test-utils';
import { createMemoryHistory, createRouter } from 'vue-router';
import LoginView from '../LoginView.vue';
import { createJwtToken } from '@/test/helpers';

const push = vi.fn();

vi.mock('@/api/auth', () => ({
  authApi: {
    login: vi.fn(),
    loginWithSms: vi.fn(),
    sendSmsCode: vi.fn(),
    register: vi.fn(),
    resetPassword: vi.fn(),
    logout: vi.fn(),
  },
}));

vi.mock('@/api/system', () => ({
  systemApi: {
    getAuthPublicConfig: vi.fn(),
    loginBootstrap: vi.fn(),
  },
}));

function buildRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/login', component: LoginView }, { path: '/rank', component: { template: '<div />' } }],
  });
}

describe('LoginView', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    push.mockReset();
    vi.clearAllMocks();
  });

  test('successful phone password login triggers bootstrap and navigates to /rank', async () => {
    const { authApi } = await import('@/api/auth');
    const { systemApi } = await import('@/api/system');
    vi.mocked(systemApi.getAuthPublicConfig).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: {
          turnstileEnabled: false,
          turnstileSiteKey: null,
        },
        timestamp: 1,
        traceId: 'trace-public-auth-config',
      },
    });
    vi.mocked(authApi.login).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: {
          accessToken: createJwtToken({
            sub: '13800138000',
            uid: 1,
            username: '13800138000',
            phone: '13800138000',
            roles: 'USER',
            iat: 2_100_000_000,
            exp: 2_100_007_200,
          }),
          tokenType: 'Bearer',
          expiresIn: 7200,
        },
        timestamp: 1,
        traceId: 'trace-login',
      },
    });
    vi.mocked(systemApi.loginBootstrap).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: { results: [] },
        timestamp: 1,
        traceId: 'trace-bootstrap',
      },
    });

    const router = buildRouter();
    await router.push('/login');
    router.push = push as typeof router.push;

    const wrapper = mount(LoginView, {
      global: {
        plugins: [router, ElementPlus],
      },
    });

    await wrapper.get('input[data-test="login-phone"]').setValue('13800138000');
    await wrapper.get('input[data-test="login-password"]').setValue('Password123');
    await wrapper.get('form').trigger('submit');
    await flushPromises();

    expect(authApi.login).toHaveBeenCalledWith({
      phone: '13800138000',
      password: 'Password123',
      deviceLabel: expect.any(String),
    });
    expect(systemApi.loginBootstrap).toHaveBeenCalledWith({ platform: 'fanqie' });
    expect(push).toHaveBeenCalledWith('/rank');
  });

  test('supports sms login entry and submits sms login payload', async () => {
    const { authApi } = await import('@/api/auth');
    const { systemApi } = await import('@/api/system');
    vi.mocked(systemApi.getAuthPublicConfig).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: {
          turnstileEnabled: false,
          turnstileSiteKey: null,
        },
        timestamp: 1,
        traceId: 'trace-public-auth-config',
      },
    });
    vi.mocked(authApi.loginWithSms).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: {
          accessToken: createJwtToken({
            sub: '13800138000',
            uid: 1,
            username: '13800138000',
            phone: '13800138000',
            roles: 'USER',
            iat: 2_100_000_000,
            exp: 2_100_007_200,
          }),
          tokenType: 'Bearer',
          expiresIn: 7200,
        },
        timestamp: 1,
        traceId: 'trace-login-sms',
      },
    });
    vi.mocked(systemApi.loginBootstrap).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: { results: [] },
        timestamp: 1,
        traceId: 'trace-bootstrap',
      },
    });

    const router = buildRouter();
    await router.push('/login');
    router.push = push as typeof router.push;

    const wrapper = mount(LoginView, {
      global: {
        plugins: [router, ElementPlus],
      },
    });

    await wrapper.get('[data-test="auth-mode-sms-login"]').trigger('click');
    await wrapper.get('input[data-test="login-phone"]').setValue('13800138000');
    await wrapper.get('input[data-test="login-sms-code"]').setValue('123456');
    await wrapper.get('form').trigger('submit');
    await flushPromises();

    expect(authApi.loginWithSms).toHaveBeenCalledWith({
      phone: '13800138000',
      smsCode: '123456',
      deviceLabel: expect.any(String),
    });
    expect(push).toHaveBeenCalledWith('/rank');
  });

  test('register requires sms code before request', async () => {
    const { authApi } = await import('@/api/auth');
    const { systemApi } = await import('@/api/system');
    vi.mocked(systemApi.getAuthPublicConfig).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: {
          turnstileEnabled: false,
          turnstileSiteKey: null,
        },
        timestamp: 1,
        traceId: 'trace-public-auth-config',
      },
    });

    const router = buildRouter();
    await router.push('/login');

    const wrapper = mount(LoginView, {
      global: {
        plugins: [router, ElementPlus],
      },
    });

    await wrapper.get('[data-test="auth-mode-register"]').trigger('click');
    await wrapper.get('input[data-test="login-phone"]').setValue('13800138000');
    await wrapper.get('input[data-test="login-password"]').setValue('Password123');
    await wrapper.get('input[data-test="register-confirm-password"]').setValue('Password123');
    await wrapper.get('form').trigger('submit');
    await flushPromises();

    expect(authApi.register).not.toHaveBeenCalled();
    expect(wrapper.text()).toContain('请输入短信验证码');
  });

  test('send sms button calls sendSmsCode with current biz type', async () => {
    const { authApi } = await import('@/api/auth');
    const { systemApi } = await import('@/api/system');
    vi.mocked(systemApi.getAuthPublicConfig).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: {
          turnstileEnabled: false,
          turnstileSiteKey: null,
        },
        timestamp: 1,
        traceId: 'trace-public-auth-config',
      },
    });
    vi.mocked(authApi.sendSmsCode).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: null,
        timestamp: 1,
        traceId: 'trace-send',
      },
    });

    const router = buildRouter();
    await router.push('/login');

    const wrapper = mount(LoginView, {
      global: {
        plugins: [router, ElementPlus],
      },
    });

    await wrapper.get('[data-test="auth-mode-register"]').trigger('click');
    await wrapper.get('input[data-test="login-phone"]').setValue('13800138000');
    await wrapper.get('[data-test="send-sms-code"]').trigger('click');
    await flushPromises();

    expect(authApi.sendSmsCode).toHaveBeenCalledWith({
      phone: '13800138000',
      bizType: 'REGISTER',
    });
  });

  test('send sms requires turnstile token before request', async () => {
    const { authApi } = await import('@/api/auth');
    const { systemApi } = await import('@/api/system');
    vi.mocked(systemApi.getAuthPublicConfig).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: {
          turnstileEnabled: true,
          turnstileSiteKey: 'site-key',
        },
        timestamp: 1,
        traceId: 'trace-public-auth-config',
      },
    });

    const router = buildRouter();
    await router.push('/login');

    const wrapper = mount(LoginView, {
      global: {
        plugins: [router, ElementPlus],
      },
    });

    await wrapper.get('[data-test="auth-mode-register"]').trigger('click');
    await wrapper.get('input[data-test="login-phone"]').setValue('13800138000');
    await wrapper.get('[data-test="send-sms-code"]').trigger('click');
    await flushPromises();

    expect(authApi.sendSmsCode).not.toHaveBeenCalled();
    expect(wrapper.text()).toContain('请完成人机校验后再发送验证码');
  });

  test('successful sms send keeps turnstile token for resend on same phone', async () => {
    const { authApi } = await import('@/api/auth');
    const { systemApi } = await import('@/api/system');
    vi.mocked(systemApi.getAuthPublicConfig).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: {
          turnstileEnabled: true,
          turnstileSiteKey: 'site-key',
        },
        timestamp: 1,
        traceId: 'trace-public-auth-config',
      },
    });
    vi.mocked(authApi.sendSmsCode).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: {
          smsOutId: 'sms-out-id',
        },
        timestamp: 1,
        traceId: 'trace-send',
      },
    } as never);

    const router = buildRouter();
    await router.push('/login');

    const wrapper = mount(LoginView, {
      global: {
        plugins: [router, ElementPlus],
      },
    });

    await wrapper.get('[data-test="auth-mode-register"]').trigger('click');
    await wrapper.get('input[data-test="login-phone"]').setValue('13800138000');
    await wrapper.getComponent({ name: 'TurnstileWidget' }).vm.$emit('verified', 'token-1');
    await flushPromises();

    await wrapper.vm.$data;
    await wrapper.get('[data-test="send-sms-code"]').trigger('click');
    await flushPromises();

    expect(authApi.sendSmsCode).toHaveBeenCalledWith({
      phone: '13800138000',
      bizType: 'REGISTER',
      turnstileToken: 'token-1',
    });

    vi.mocked(authApi.sendSmsCode).mockClear();
    (wrapper.vm as { state: { smsCountdown: number } }).state.smsCountdown = 0;
    await (wrapper.vm as { sendSmsCode: () => Promise<void> }).sendSmsCode();
    await flushPromises();

    expect(authApi.sendSmsCode).toHaveBeenCalledWith({
      phone: '13800138000',
      bizType: 'REGISTER',
      turnstileToken: 'token-1',
    });
  });

  test('changing phone clears turnstile token and requires verification again', async () => {
    const { authApi } = await import('@/api/auth');
    const { systemApi } = await import('@/api/system');
    vi.mocked(systemApi.getAuthPublicConfig).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: {
          turnstileEnabled: true,
          turnstileSiteKey: 'site-key',
        },
        timestamp: 1,
        traceId: 'trace-public-auth-config',
      },
    });

    const router = buildRouter();
    await router.push('/login');

    const wrapper = mount(LoginView, {
      global: {
        plugins: [router, ElementPlus],
      },
    });

    await wrapper.get('[data-test="auth-mode-register"]').trigger('click');
    await wrapper.get('input[data-test="login-phone"]').setValue('13800138000');
    await wrapper.getComponent({ name: 'TurnstileWidget' }).vm.$emit('verified', 'token-1');
    await flushPromises();

    await wrapper.get('input[data-test="login-phone"]').setValue('13900139000');
    await flushPromises();
    await wrapper.get('[data-test="send-sms-code"]').trigger('click');
    await flushPromises();

    expect(authApi.sendSmsCode).not.toHaveBeenCalled();
    expect(wrapper.text()).toContain('请完成人机校验后再发送验证码');
  });

  test('shows debug verify code when sms send response returns one', async () => {
    const { authApi } = await import('@/api/auth');
    const { systemApi } = await import('@/api/system');
    vi.mocked(systemApi.getAuthPublicConfig).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: {
          turnstileEnabled: false,
          turnstileSiteKey: null,
        },
        timestamp: 1,
        traceId: 'trace-public-auth-config',
      },
    });
    vi.mocked(authApi.sendSmsCode).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: {
          debugVerifyCode: 'fe0orl',
        },
        timestamp: 1,
        traceId: 'trace-send-debug',
      },
    } as never);

    const router = buildRouter();
    await router.push('/login');

    const wrapper = mount(LoginView, {
      global: {
        plugins: [router, ElementPlus],
      },
    });

    await wrapper.get('[data-test="auth-mode-register"]').trigger('click');
    await wrapper.get('input[data-test="login-phone"]').setValue('13800138000');
    await wrapper.get('[data-test="send-sms-code"]').trigger('click');
    await flushPromises();

    expect(wrapper.text()).toContain('本地调试验证码');
    expect(wrapper.text()).toContain('fe0orl');
  });

  test('reset password submits phone, sms code and new password then returns to password login', async () => {
    const { authApi } = await import('@/api/auth');
    const { systemApi } = await import('@/api/system');
    vi.mocked(systemApi.getAuthPublicConfig).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: {
          turnstileEnabled: false,
          turnstileSiteKey: null,
        },
        timestamp: 1,
        traceId: 'trace-public-auth-config',
      },
    });
    vi.mocked(authApi.resetPassword).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: null,
        timestamp: 1,
        traceId: 'trace-reset',
      },
    });

    const router = buildRouter();
    await router.push('/login');

    const wrapper = mount(LoginView, {
      global: {
        plugins: [router, ElementPlus],
      },
    });

    await wrapper.get('[data-test="auth-mode-reset"]').trigger('click');
    await wrapper.get('input[data-test="login-phone"]').setValue('13800138000');
    await wrapper.get('input[data-test="login-sms-code"]').setValue('123456');
    await wrapper.get('input[data-test="login-password"]').setValue('NewPassword123');
    await wrapper.get('input[data-test="register-confirm-password"]').setValue('NewPassword123');
    await wrapper.get('form').trigger('submit');
    await flushPromises();

    expect(authApi.resetPassword).toHaveBeenCalledWith({
      phone: '13800138000',
      smsCode: '123456',
      newPassword: 'NewPassword123',
    });
    await flushPromises();
    expect(wrapper.text()).toContain('账号登录');
  });
});
