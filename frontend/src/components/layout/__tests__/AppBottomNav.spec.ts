import fs from 'node:fs';
import path from 'node:path';
import { mount } from '@vue/test-utils';
import { createMemoryHistory, createRouter } from 'vue-router';
import AppBottomNav from '../AppBottomNav.vue';

const routes = [
  { path: '/rank', component: { template: '<div />' } },
  { path: '/analysis', component: { template: '<div />' } },
  { path: '/trend', component: { template: '<div />' } },
  { path: '/history', component: { template: '<div />' } },
];

describe('AppBottomNav', () => {
  test('avoids viewport-width sizing that can trigger horizontal overflow in mobile emulation', () => {
    const source = fs.readFileSync(path.resolve(__dirname, '../AppBottomNav.vue'), 'utf-8');

    expect(source).not.toMatch(/100vw/);
  });

  test('renders four nav items and highlights active path', async () => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes,
    });
    await router.push('/trend');
    const wrapper = mount(AppBottomNav, {
      global: {
        plugins: [router],
      },
    });

    const links = wrapper.findAll('.app-bottom-nav__link');
    expect(links).toHaveLength(4);
    expect(links[2].text()).toBe('趋势分析');
    expect(links[2].classes()).toContain('is-active');
  });
});
