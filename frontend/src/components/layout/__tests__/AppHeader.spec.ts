import fs from 'node:fs';
import path from 'node:path';
import ElementPlus from 'element-plus';
import { mount } from '@vue/test-utils';
import { createMemoryHistory, createRouter } from 'vue-router';
import AppHeader from '../AppHeader.vue';

async function mountHeader(pathname: string) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/knowledge', component: { template: '<div />' } },
      { path: '/rank', component: { template: '<div />' } },
    ],
  });
  await router.push(pathname);

  return mount(AppHeader, {
    props: {
      username: 'demo',
      roles: ['USER'],
    },
    global: {
      plugins: [router, ElementPlus],
    },
  });
}

describe('AppHeader styles', () => {
  test('mobile fixed header avoids explicit full-width sizing that can cause horizontal overflow', () => {
    const source = fs.readFileSync(path.resolve(__dirname, '../AppHeader.vue'), 'utf-8');
    const mobileStyles = source.split('@media (max-width: 768px)')[1] ?? '';

    expect(mobileStyles).not.toContain('width: 100%;');
  });

  test('uses lighter glass effects on the fixed header to reduce scroll jank', () => {
    const source = fs.readFileSync(path.resolve(__dirname, '../AppHeader.vue'), 'utf-8');

    expect(source).not.toContain('blur(18px) saturate(1.2)');
    expect(source).not.toContain('blur(14px)');
  });

  test('exposes password change entry in desktop and mobile account controls', () => {
    const source = fs.readFileSync(path.resolve(__dirname, '../AppHeader.vue'), 'utf-8');

    expect(source).toContain('changePassword');
    expect(source.match(/emit\('changePassword'\)/g)?.length).toBeGreaterThanOrEqual(2);
    expect(source).toContain('app-header__avatar-button');
  });

  test('shows a mobile project-space action only on the AI chat route', async () => {
    const knowledgeHeader = await mountHeader('/knowledge');
    const projectButton = knowledgeHeader.find('[data-test="knowledge-mobile-project-open"]');

    expect(projectButton.exists()).toBe(true);
    await projectButton.trigger('click');
    expect(knowledgeHeader.emitted('openKnowledgeProjects')).toHaveLength(1);

    const rankHeader = await mountHeader('/rank');
    expect(rankHeader.find('[data-test="knowledge-mobile-project-open"]').exists()).toBe(false);
  });

  test('keeps mobile account controls square instead of allowing flex compression', () => {
    const source = fs.readFileSync(path.resolve(__dirname, '../AppHeader.vue'), 'utf-8');

    expect(source).toContain('aria-label="切换主题"');
    expect(source).toMatch(/\.app-header__avatar-button\s*\{[\s\S]*?flex:\s*0 0 44px;[\s\S]*?width:\s*44px;[\s\S]*?height:\s*44px;/);
    expect(source).toMatch(/\.app-header__mobile-project\s*\{[\s\S]*?width:\s*44px;[\s\S]*?min-width:\s*44px;[\s\S]*?height:\s*44px;/);
    expect(source).toMatch(/\.app-header__mobile-theme\s*\{[\s\S]*?width:\s*44px;[\s\S]*?min-width:\s*44px;[\s\S]*?height:\s*44px;/);
  });

  test('gives account controls bounded lift and press feedback', () => {
    const source = fs.readFileSync(path.resolve(__dirname, '../AppHeader.vue'), 'utf-8');

    expect(source).toContain('var(--motion-spring)');
    expect(source).toContain('var(--motion-press-scale)');
    expect(source).toContain('@media (hover: hover) and (pointer: fine)');
  });
});
