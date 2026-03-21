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

describe('LoginView', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    push.mockReset();
  });

  test('successful login stores session and navigates to /rank', async () => {
    const { authApi } = await import('@/api/auth');
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

    await wrapper.get('input[placeholder="请输入用户名"]').setValue('demo');
    await wrapper.get('input[type="password"]').setValue('password');
    await wrapper.get('form').trigger('submit');
    await flushPromises();

    expect(authApi.login).toHaveBeenCalled();
    expect(push).toHaveBeenCalledWith('/rank');
  });

  test('failed login displays backend message', async () => {
    const { authApi } = await import('@/api/auth');
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

    await wrapper.get('input[placeholder="请输入用户名"]').setValue('demo');
    await wrapper.get('input[type="password"]').setValue('wrong');
    await wrapper.get('form').trigger('submit');
    await flushPromises();

    expect(wrapper.text()).toContain('bad credentials');
    expect(wrapper.text()).toContain('trace-failed');
  });
});
