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
});
