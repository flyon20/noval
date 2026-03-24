import { createRouter, createWebHistory } from 'vue-router';
import { restoreSessionFromStorage } from '@/lib/auth-session';
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

router.beforeEach((to) => {
  const session = restoreSessionFromStorage();
  const redirect = resolveAuthRedirect(
    {
      path: to.path,
      meta: {
        public: Boolean(to.meta.public),
        roles: Array.isArray(to.meta.roles) ? (to.meta.roles as string[]) : undefined,
      },
    },
    !!session,
    session?.roles ?? [],
  );

  return redirect ?? true;
});

export default router;
