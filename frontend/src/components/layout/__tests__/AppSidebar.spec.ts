import fs from 'node:fs';
import path from 'node:path';
import ElementPlus from 'element-plus';
import { mount } from '@vue/test-utils';
import { createMemoryHistory, createRouter } from 'vue-router';
import AppSidebar from '../AppSidebar.vue';

async function mountSidebar(roles: string[] = ['USER']) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/rank', component: { template: '<div />' } }],
  });
  await router.push('/rank');

  return mount(AppSidebar, {
    props: {
      roles,
      showKnowledgeSpaceAction: true,
    },
    global: {
      plugins: [router, ElementPlus],
    },
  });
}

describe('AppSidebar', () => {
  test('reveals its subtle scrollbar during scrolling and hides it afterwards', async () => {
    vi.useFakeTimers();
    const wrapper = await mountSidebar();

    try {
      const sidebar = wrapper.get('aside');
      await sidebar.trigger('scroll');

      expect(sidebar.classes()).toContain('is-scrolling');
      vi.advanceTimersByTime(850);
      expect(sidebar.classes()).not.toContain('is-scrolling');
    } finally {
      wrapper.unmount();
      vi.useRealTimers();
    }
  });

  test('keeps desktop scrollbars transparent until scrolling or keyboard focus', () => {
    const source = fs.readFileSync(path.resolve(__dirname, '../../../styles/base.scss'), 'utf-8');

    expect(source).toContain('scrollbar-color: transparent transparent;');
    expect(source).toContain('.app-sidebar.is-scrolling');
    expect(source).toContain('.knowledge-project-space.is-scrolling');
    expect(source).toContain('.app-sidebar:focus-within');
    expect(source).toContain('.knowledge-project-space:focus-within');
  });

  test('names the administrator surface as memory review', async () => {
    const wrapper = await mountSidebar(['ADMIN']);

    expect(wrapper.text()).toContain('记忆审核');
    expect(wrapper.text()).not.toContain('记忆管理');
  });

  test('uses shared spring and press motion without changing link geometry', () => {
    const source = fs.readFileSync(path.resolve(__dirname, '../AppSidebar.vue'), 'utf-8');

    expect(source).toContain('var(--motion-spring)');
    expect(source).toContain('var(--motion-press-scale)');
    expect(source).toContain('@media (prefers-reduced-motion: reduce)');
  });

  test('uses one XPBD indicator for desktop primary navigation continuity', () => {
    const source = fs.readFileSync(path.resolve(__dirname, '../AppSidebar.vue'), 'utf-8');

    expect(source).toContain('useXpbdSelector');
    expect(source).toContain('app-sidebar__indicator');
    expect(source).toContain('--sidebar-indicator-position');
  });
});
