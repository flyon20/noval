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
          results: [
            {
              channelCode: 'male-new',
              boardCode: 'urban-brain',
              snapshotId: 6001,
              total: 2,
              reused: false,
              refreshLimited: false,
              analysisTriggered: false,
            },
          ],
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
    await wrapper.get('input[type="password"]').setValue('password');
    await wrapper.get('form').trigger('submit');
    await flushPromises();

    expect(authApi.login).toHaveBeenCalled();
    expect(systemApi.loginBootstrap).toHaveBeenCalledWith({ platform: 'fanqie' });
    expect(push).toHaveBeenCalledWith('/rank');
  });

  test('navigates to /rank even when bootstrap is still pending', async () => {
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
    vi.mocked(systemApi.loginBootstrap).mockImplementation(() => new Promise(() => {}));

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
    await wrapper.get('input[type="password"]').setValue('password');
    await wrapper.get('form').trigger('submit');
    await flushPromises();

    expect(systemApi.loginBootstrap).toHaveBeenCalledWith({ platform: 'fanqie' });
    expect(push).toHaveBeenCalledWith('/rank');
  });

  test('renders compact project intro without JWT copy', async () => {
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

    expect(wrapper.text()).toContain('NOVAL');
    expect(wrapper.text()).toContain('账号登录');
    expect(wrapper.text()).toContain('进入工作台');
    expect(wrapper.text()).not.toContain('JWT');
  });

  test('failed login displays backend message', async () => {
    const { authApi } = await import('@/api/auth');
    const { systemApi } = await import('@/api/system');
    vi.mocked(authApi.login).mockRejectedValue({
      response: {
        status: 401,
        data: {
          message: 'bad credentials',
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
    await wrapper.get('input[type="password"]').setValue('wrong');
    await wrapper.get('form').trigger('submit');
    await flushPromises();

    expect(systemApi.loginBootstrap).not.toHaveBeenCalled();
    expect(wrapper.text()).toContain('bad credentials');
    expect(wrapper.text()).toContain('trace-failed');
  });
});
