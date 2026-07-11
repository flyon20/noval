import { createRouter, createWebHistory } from 'vue-router';
import { useAuthStore } from '@/stores/auth';
import { resolveAuthRedirect } from '@/router/guards';

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/login',
      component: () => import('@/views/login/LoginView.vue'),
      meta: {
        public: true,
      },
    },
    {
      path: '/',
      component: () => import('@/layouts/ProtectedLayout.vue'),
      children: [
        {
          path: '',
          redirect: '/rank',
        },
        {
          path: 'rank',
          component: () => import('@/views/rank/RankView.vue'),
        },
        {
          path: 'analysis',
          component: () => import('@/views/analysis/AnalysisView.vue'),
        },
        {
          path: 'trend',
          component: () => import('@/views/trend/TrendView.vue'),
        },
        {
          path: 'knowledge',
          component: () => import('@/views/knowledge/KnowledgeChatView.vue'),
        },
        {
          path: 'knowledge/admin/traces',
          component: () => import('@/views/knowledge/AdminAgentTraceView.vue'),
          meta: {
            roles: ['ADMIN'],
          },
        },
        {
          path: 'knowledge/admin/agent-governance',
          component: () => import('@/views/knowledge/AdminAgentGovernanceView.vue'),
          meta: {
            roles: ['ADMIN'],
          },
        },
        {
          path: 'knowledge/admin/skills',
          component: () => import('@/views/knowledge/AdminSkillGovernanceView.vue'),
          meta: {
            roles: ['ADMIN'],
          },
        },
        {
          path: 'knowledge/admin/memories',
          component: () => import('@/views/knowledge/AdminMemoryView.vue'),
          meta: {
            roles: ['ADMIN'],
          },
        },
        {
          path: 'history',
          component: () => import('@/views/history/HistoryView.vue'),
        },
        {
          path: 'config/prompt',
          component: () => import('@/views/config/prompt/PromptConfigView.vue'),
          meta: {
            roles: ['ADMIN', 'USER'],
          },
        },
        {
          path: 'config/system',
          component: () => import('@/views/config/system/SystemConfigView.vue'),
          meta: {
            roles: ['ADMIN'],
          },
        },
      ],
    },
  ],
});

router.beforeEach(async (to) => {
  const authStore = useAuthStore();
  await authStore.ensureAuthRestored();

  const redirect = resolveAuthRedirect(
    {
      path: to.path,
      meta: {
        public: Boolean(to.meta.public),
        roles: Array.isArray(to.meta.roles) ? (to.meta.roles as string[]) : undefined,
      },
    },
    authStore.restoreStatus,
    authStore.session?.roles ?? [],
  );

  return redirect ?? true;
});

export default router;
