import type { AuthRestoreStatus } from '@/types/auth';

interface GuardRouteLike {
  path: string;
  meta?: {
    public?: boolean;
    roles?: string[];
  };
}

export function resolveAuthRedirect(
  to: GuardRouteLike,
  authStatus: AuthRestoreStatus,
  currentRoles: string[] = [],
) {
  if (authStatus === 'restoring') {
    return null;
  }

  if (authStatus === 'logged_out' && !to.meta?.public) {
    return '/login';
  }

  if (authStatus === 'authenticated' && to.path === '/login') {
    return '/rank';
  }

  if (authStatus === 'authenticated' && to.meta?.roles?.length) {
    const canAccess = to.meta.roles.some((role) => currentRoles.includes(role));

    if (!canAccess) {
      return '/rank';
    }
  }

  return null;
}
