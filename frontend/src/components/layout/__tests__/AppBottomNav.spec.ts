import fs from 'node:fs';
import path from 'node:path';
import { mount } from '@vue/test-utils';
import { createMemoryHistory, createRouter } from 'vue-router';
import AppBottomNav from '../AppBottomNav.vue';

const routes = [
  { path: '/rank', component: { template: '<div />' } },
  { path: '/analysis', component: { template: '<div />' } },
  { path: '/knowledge', component: { template: '<div />' } },
  { path: '/trend', component: { template: '<div />' } },
  { path: '/history', component: { template: '<div />' } },
];

describe('AppBottomNav', () => {
  test('avoids viewport-width sizing that can trigger horizontal overflow in mobile emulation', () => {
    const source = fs.readFileSync(path.resolve(__dirname, '../AppBottomNav.vue'), 'utf-8');

    expect(source).not.toMatch(/100vw/);
  });

  test('keeps the floating nav blur and shadow lightweight for smoother scrolling', () => {
    const source = fs.readFileSync(path.resolve(__dirname, '../AppBottomNav.vue'), 'utf-8');

    expect(source).not.toContain('blur(24px)');
    expect(source).not.toContain('0 20px 44px');
  });

  test('renders five primary nav items with the AI entry centered and emphasized', async () => {
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
    expect(links).toHaveLength(5);
    expect(links[2].text()).toBe('AI 问答');
    expect(links[2].classes()).toContain('is-primary');
    expect(links[3].classes()).toContain('is-active');
  });

  test('uses five nav columns so history remains visible on mobile', () => {
    const source = fs.readFileSync(path.resolve(__dirname, '../AppBottomNav.vue'), 'utf-8');

    expect(source).toContain('repeat(5, minmax(0, 1fr))');
  });
});
