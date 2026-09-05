import ElementPlus from 'element-plus';
import fs from 'node:fs';
import path from 'node:path';
import { flushPromises, mount } from '@vue/test-utils';
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

  test('defines one bounded motion contract with a global reduced-motion fallback', () => {
    const tokenSource = fs.readFileSync(path.resolve(__dirname, '../../styles/tokens.scss'), 'utf-8');
    const baseSource = fs.readFileSync(path.resolve(__dirname, '../../styles/base.scss'), 'utf-8');

    expect(tokenSource).toContain('--motion-spring:');
    expect(tokenSource).toContain('--motion-press-scale:');
    expect(baseSource).toContain('@media (prefers-reduced-motion: reduce)');
    expect(baseSource).toContain('transition-duration: 1ms !important;');
  });

  test('adds a static workspace field and bounded lift only to actionable surfaces', () => {
    const source = fs.readFileSync(path.resolve(__dirname, '../AppShell.vue'), 'utf-8');

    expect(source).toContain('--workspace-grid-size: 64px;');
    expect(source).toContain('.app-shell__surface::before');
    expect(source).toContain('@media (hover: hover) and (pointer: fine)');
    expect(source).toContain('transform: translateY(-2px);');
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
    expect(sidebarSource).toContain('/knowledge/admin/agent-governance');
    expect(sidebarSource).toContain('/knowledge/admin/skills');
    expect(sidebarSource).toContain('/knowledge/admin/memories');
    expect(sidebarSource).toContain("props.roles.includes('ADMIN')");
  });

  test('switches the desktop knowledge route sidebar to the project space', () => {
    const source = fs.readFileSync(path.resolve(__dirname, '../AppShell.vue'), 'utf-8');

    expect(source).toContain('KnowledgeProjectSpace');
    expect(source).toContain('knowledge-sidebar-mode');
    expect(source).toContain('showMainNavAction');
  });

  test('hosts the mobile knowledge project drawer from the shell at half width', () => {
    const source = fs.readFileSync(path.resolve(__dirname, '../AppShell.vue'), 'utf-8');

    expect(source).toContain('mobileProjectDrawerVisible');
    expect(source).toContain('@open-knowledge-projects');
    expect(source).toContain('data-test="knowledge-mobile-project-drawer"');
    expect(source).toContain('size="50%"');
  });

  test('constrains knowledge chat rows to the available shell width', () => {
    const source = fs.readFileSync(
      path.resolve(__dirname, '../../views/knowledge/KnowledgeChatView.vue'),
      'utf-8',
    );

    expect(source).toMatch(
      /\.knowledge-chat\s*\{[^}]*grid-template-columns:\s*minmax\(0, 1fr\);/s,
    );
    expect(source).toMatch(
      /\.knowledge-chat__toolbar,[^{]+\{[^}]*min-width:\s*0;[^}]*max-width:\s*100%;/s,
    );
    expect(source).toMatch(
      /\.knowledge-chat__skill-shortcuts\s*\{[^}]*min-width:\s*0;[^}]*max-width:\s*100%;/s,
    );
    expect(source).toMatch(
      /\.knowledge-chat__skill-options\s*\{[^}]*min-width:\s*0;[^}]*max-width:\s*100%;[^}]*overflow-x:\s*auto;/s,
    );
    expect(source).toMatch(
      /\.knowledge-chat__skill-option\s*\{[^}]*min-height:\s*44px;/s,
    );
  });

  test('locks document scrolling only while the mobile knowledge chat route is active', () => {
    const source = fs.readFileSync(path.resolve(__dirname, '../AppShell.vue'), 'utf-8');
    const baseSource = fs.readFileSync(path.resolve(__dirname, '../../styles/base.scss'), 'utf-8');

    expect(source).toContain('knowledge-chat-route');
    expect(source).toContain("document.documentElement.classList.toggle('knowledge-chat-route', locked)");
    expect(source).toContain("document.body.classList.toggle('knowledge-chat-route', locked)");
    expect(source).toContain("syncKnowledgeRouteScrollLock(false)");
    expect(baseSource).toContain('body.knowledge-chat-route');
    expect(baseSource).toContain('padding-bottom: 0;');
  });

  test('cleans the document scroll lock when leaving the knowledge route', async () => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/knowledge', component: { template: '<div />' } },
        { path: '/rank', component: { template: '<div />' } },
      ],
    });
    await router.push('/knowledge');

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
        stubs: {
          KnowledgeProjectSpace: true,
        },
      },
    });

    await flushPromises();
    expect(document.documentElement.classList.contains('knowledge-chat-route')).toBe(true);
    expect(document.body.classList.contains('knowledge-chat-route')).toBe(true);

    await router.push('/rank');
    await flushPromises();
    expect(document.documentElement.classList.contains('knowledge-chat-route')).toBe(false);
    expect(document.body.classList.contains('knowledge-chat-route')).toBe(false);

    wrapper.unmount();
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
