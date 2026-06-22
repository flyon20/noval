import ElementPlus from 'element-plus';
import fs from 'node:fs';
import path from 'node:path';
import { mount } from '@vue/test-utils';
import { createMemoryHistory, createRouter } from 'vue-router';
import AppShell from '../AppShell.vue';

describe('AppShell', () => {
  test('adds scroll-performance overrides for high-frequency glass surfaces', () => {
    const source = fs.readFileSync(path.resolve(__dirname, '../AppShell.vue'), 'utf-8');

    expect(source).toContain(':deep(.rank-page__item)');
    expect(source).toContain(':deep(.trend-chart-card)');
    expect(source).toContain(':deep(.analysis-result-card)');
    expect(source).toContain('backdrop-filter: none;');
  });

  test('keeps desktop sidebar fixed while preserving mobile hidden navigation', () => {
    const shellSource = fs.readFileSync(path.resolve(__dirname, '../AppShell.vue'), 'utf-8');
    const sidebarSource = fs.readFileSync(path.resolve(__dirname, '../../components/layout/AppSidebar.vue'), 'utf-8');

    expect(sidebarSource).toContain('position: fixed;');
    expect(sidebarSource).toContain('max-height: calc(100dvh - 2.7rem);');
    expect(sidebarSource).toContain('overflow-y: auto;');
    expect(sidebarSource).toContain('@media (max-width: 980px) and (min-width: 769px)');
    expect(shellSource).not.toContain('position: static;');
    expect(shellSource).toContain('@media (max-width: 768px)');
    expect(shellSource).toContain('display: none;');
  });

  test('keeps admin agent panels reachable from the desktop sidebar', () => {
    const sidebarSource = fs.readFileSync(path.resolve(__dirname, '../../components/layout/AppSidebar.vue'), 'utf-8');

    expect(sidebarSource).toContain('/knowledge/admin/traces');
    expect(sidebarSource).toContain('/knowledge/admin/skills');
    expect(sidebarSource).toContain("props.roles.includes('ADMIN')");
  });

  test('does not clip desktop page-level sticky controls', () => {
    const source = fs.readFileSync(path.resolve(__dirname, '../AppShell.vue'), 'utf-8');

    expect(source).toContain('.app-shell__surface');
    expect(source).toContain('overflow: visible;');
  });

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
    expect(wrapper.text()).toContain('修改密码');
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
