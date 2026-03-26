import ElementPlus from 'element-plus';
import { mount } from '@vue/test-utils';
import { createMemoryHistory, createRouter } from 'vue-router';
import AppShell from '../AppShell.vue';

describe('AppShell', () => {
  test('renders app shell slots and top actions', async () => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/rank', component: { template: '<div />' } }],
    });
    await router.push('/rank');

    const wrapper = mount(AppShell, {
      props: {
        username: 'demo',
        roles: ['USER'],
      },
      slots: {
        default: '<div>page body</div>',
      },
      global: {
        plugins: [router, ElementPlus],
      },
    });

    expect(wrapper.text()).toContain('demo');
    expect(wrapper.text()).toContain('page body');
  });

  test('renders content with an existing theme attribute', async () => {
    const previousTheme = document.documentElement.dataset.theme;
    document.documentElement.dataset.theme = 'dark';

    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/rank', component: { template: '<div />' } }],
    });
    await router.push('/rank');

    const wrapper = mount(AppShell, {
      props: {
        username: 'demo',
        roles: ['USER'],
      },
      slots: {
        default: '<div>page body</div>',
      },
      global: {
        plugins: [router, ElementPlus],
      },
    });

    expect(wrapper.text()).toContain('page body');
    document.documentElement.dataset.theme = previousTheme || '';
  });

  test('keeps header and bottom navigation mounted for mobile shell layout', async () => {
    const originalWidth = window.innerWidth;
    Object.defineProperty(window, 'innerWidth', {
      configurable: true,
      writable: true,
      value: 390,
    });

    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/rank', component: { template: '<div />' } }],
    });
    await router.push('/rank');

    const wrapper = mount(AppShell, {
      props: {
        username: 'demo',
        roles: ['USER'],
      },
      slots: {
        default: '<div>page body</div>',
      },
      global: {
        plugins: [router, ElementPlus],
      },
    });

    expect(wrapper.findComponent({ name: 'AppHeader' }).exists()).toBe(true);
    expect(wrapper.findComponent({ name: 'AppBottomNav' }).exists()).toBe(true);

    Object.defineProperty(window, 'innerWidth', {
      configurable: true,
      writable: true,
      value: originalWidth,
    });
  });
});
