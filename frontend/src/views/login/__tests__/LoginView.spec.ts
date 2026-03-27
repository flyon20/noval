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
    register: vi.fn(),
    logout: vi.fn(),
  },
}));

vi.mock('@/api/system', () => ({
  systemApi: {
    loginBootstrap: vi.fn(),
  },
}));

describe('LoginView', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    push.mockReset();
    vi.clearAllMocks();
  });

  test('successful login triggers bootstrap and navigates to /rank', async () => {
    const { authApi } = await import('@/api/auth');
    const { systemApi } = await import('@/api/system');
    vi.mocked(authApi.login).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: {
          accessToken: createJwtToken({
            sub: 'demo',
            uid: 1,
            username: 'demo',
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
        data: {
          results: [],
        },
        timestamp: 1,
        traceId: 'trace-bootstrap',
      },
    });

    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/login', component: LoginView }, { path: '/rank', component: { template: '<div />' } }],
    });
    await router.push('/login');
    router.push = push as typeof router.push;

    const wrapper = mount(LoginView, {
      global: {
        plugins: [router, ElementPlus],
      },
    });

    await wrapper.get('input[autocomplete="username"]').setValue('demo');
    await wrapper.get('input[autocomplete="current-password"]').setValue('password');
    await wrapper.get('form').trigger('submit');
    await flushPromises();

    expect(authApi.login).toHaveBeenCalledWith({
      username: 'demo',
      password: 'password',
      deviceLabel: expect.any(String),
    });
    expect(systemApi.loginBootstrap).toHaveBeenCalledWith({ platform: 'fanqie' });
    expect(push).toHaveBeenCalledWith('/rank');
  });

  test('successful register triggers bootstrap and navigates to /rank', async () => {
    const { authApi } = await import('@/api/auth');
    const { systemApi } = await import('@/api/system');
    vi.mocked(authApi.register).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: {
          accessToken: createJwtToken({
            sub: 'new-user',
            uid: 3,
            username: 'new-user',
            roles: 'USER',
            iat: 2_100_000_000,
            exp: 2_100_007_200,
          }),
          tokenType: 'Bearer',
          expiresIn: 7200,
        },
        timestamp: 1,
        traceId: 'trace-register',
      },
    });
    vi.mocked(systemApi.loginBootstrap).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: {
          results: [],
        },
        timestamp: 1,
        traceId: 'trace-bootstrap',
      },
    });

    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/login', component: LoginView }, { path: '/rank', component: { template: '<div />' } }],
    });
    await router.push('/login');
    router.push = push as typeof router.push;

    const wrapper = mount(LoginView, {
      global: {
        plugins: [router, ElementPlus],
      },
    });

    await wrapper.get('[data-test="auth-mode-register"]').trigger('click');
    await wrapper.get('input[autocomplete="username"]').setValue('new-user');
    await wrapper.get('input[autocomplete="new-password"]').setValue('Password123');
    await wrapper.get('input[data-test="register-confirm-password"]').setValue('Password123');
    await wrapper.get('form').trigger('submit');
    await flushPromises();

    expect(authApi.register).toHaveBeenCalledWith({
      username: 'new-user',
      password: 'Password123',
      deviceLabel: expect.any(String),
    });
    expect(systemApi.loginBootstrap).toHaveBeenCalledWith({ platform: 'fanqie' });
    expect(push).toHaveBeenCalledWith('/rank');
  });

  test('register mode validates confirm password before request', async () => {
    const { authApi } = await import('@/api/auth');

    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/login', component: LoginView }],
    });
    await router.push('/login');

    const wrapper = mount(LoginView, {
      global: {
        plugins: [router, ElementPlus],
      },
    });

    await wrapper.get('[data-test="auth-mode-register"]').trigger('click');
    await wrapper.get('input[autocomplete="username"]').setValue('new-user');
    await wrapper.get('input[autocomplete="new-password"]').setValue('Password123');
    await wrapper.get('input[data-test="register-confirm-password"]').setValue('Password456');
    await wrapper.get('form').trigger('submit');
    await flushPromises();

    expect(authApi.register).not.toHaveBeenCalled();
    expect(wrapper.text()).toContain('两次输入的密码不一致');
  });

  test('register mode hides the password rules panel and still blocks weak passwords before request', async () => {
    const { authApi } = await import('@/api/auth');

    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/login', component: LoginView }],
    });
    await router.push('/login');

    const wrapper = mount(LoginView, {
      global: {
        plugins: [router, ElementPlus],
      },
    });

    await wrapper.get('[data-test="auth-mode-register"]').trigger('click');

    expect(wrapper.text()).not.toContain('密码设置要求');
    expect(wrapper.text()).not.toContain('包含大写字母');

    await wrapper.get('input[autocomplete="username"]').setValue('new-user');
    await wrapper.get('input[autocomplete="new-password"]').setValue('secret123');
    await wrapper.get('input[data-test="register-confirm-password"]').setValue('secret123');
    await wrapper.get('form').trigger('submit');
    await flushPromises();

    expect(authApi.register).not.toHaveBeenCalled();
    expect(wrapper.text()).toContain('密码需至少 8 位，且包含大写字母、小写字母和数字');
  });

  test('register mode upgrades placeholders to explain username and password requirements', async () => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/login', component: LoginView }],
    });
    await router.push('/login');

    const wrapper = mount(LoginView, {
      global: {
        plugins: [router, ElementPlus],
      },
    });

    await wrapper.get('[data-test="auth-mode-register"]').trigger('click');
    await flushPromises();

    expect(wrapper.get('input[autocomplete="username"]').attributes('placeholder')).toBe('创建用户名，用于后续登录');
    expect(wrapper.get('input[autocomplete="new-password"]').attributes('placeholder')).toBe('设置密码，至少 8 位，含大小写字母和数字');
    expect(wrapper.get('input[data-test="register-confirm-password"]').attributes('placeholder')).toBe('再次输入密码，需与上方一致');
  });

  test('failed login displays backend message', async () => {
    const { authApi } = await import('@/api/auth');
    const { systemApi } = await import('@/api/system');
    vi.mocked(authApi.login).mockRejectedValue({
      response: {
        status: 401,
        data: {
          message: '密码错误，请重新输入',
          traceId: 'trace-failed',
        },
      },
    });

    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/login', component: LoginView }],
    });
    await router.push('/login');

    const wrapper = mount(LoginView, {
      global: {
        plugins: [router, ElementPlus],
      },
    });

    await wrapper.get('input[autocomplete="username"]').setValue('demo');
    await wrapper.get('input[autocomplete="current-password"]').setValue('wrong');
    await wrapper.get('form').trigger('submit');
    await flushPromises();

    expect(systemApi.loginBootstrap).not.toHaveBeenCalled();
    expect(wrapper.text()).toContain('密码错误，请重新输入');
    expect(wrapper.text()).toContain('trace-failed');
  });

  test('failed login hides odd backend message and shows friendly reason copy', async () => {
    const { authApi } = await import('@/api/auth');
    const { systemApi } = await import('@/api/system');
    vi.mocked(authApi.login).mockRejectedValue({
      response: {
        status: 401,
        data: {
          message: '40101',
          traceId: 'trace-odd',
        },
      },
    });

    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/login', component: LoginView }],
    });
    await router.push('/login');

    const wrapper = mount(LoginView, {
      global: {
        plugins: [router, ElementPlus],
      },
    });

    await wrapper.get('input[autocomplete="username"]').setValue('demo');
    await wrapper.get('input[autocomplete="current-password"]').setValue('wrong');
    await wrapper.get('form').trigger('submit');
    await flushPromises();

    expect(systemApi.loginBootstrap).not.toHaveBeenCalled();
    expect(wrapper.text()).toContain('登录失败，请检查用户名和密码');
    expect(wrapper.text()).not.toContain('40101');
    expect(wrapper.text()).toContain('trace-odd');
  });

  test('failed register explains unauthorized backend response with a friendly reason', async () => {
    const { authApi } = await import('@/api/auth');
    vi.mocked(authApi.register).mockRejectedValue({
      response: {
        status: 401,
        data: {
          message: 'unauthorized',
          traceId: 'trace-register-unauthorized',
        },
      },
    });

    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/login', component: LoginView }],
    });
    await router.push('/login');

    const wrapper = mount(LoginView, {
      global: {
        plugins: [router, ElementPlus],
      },
    });

    await wrapper.get('[data-test="auth-mode-register"]').trigger('click');
    await wrapper.get('input[autocomplete="username"]').setValue('new-user');
    await wrapper.get('input[autocomplete="new-password"]').setValue('Password123');
    await wrapper.get('input[data-test="register-confirm-password"]').setValue('Password123');
    await wrapper.get('form').trigger('submit');
    await flushPromises();

    expect(authApi.register).toHaveBeenCalled();
    expect(wrapper.text()).toContain('注册入口暂时不可用，请稍后重试');
    expect(wrapper.text()).toContain('trace-register-unauthorized');
  });
});
