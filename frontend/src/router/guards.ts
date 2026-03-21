interface GuardRouteLike {
  path: string;
  meta?: {
    public?: boolean;
    roles?: string[];
  };
}

export function resolveAuthRedirect(
  to: GuardRouteLike,
  isAuthenticated: boolean,
  currentRoles: string[] = [],
) {
  if (!isAuthenticated && !to.meta?.public) {
    return '/login';
  }

  if (isAuthenticated && to.path === '/login') {
    return '/rank';
  }

  if (to.meta?.roles?.length) {
    const canAccess = to.meta.roles.some((role) => currentRoles.includes(role));

    if (!canAccess) {
      return '/rank';
    }
  }

  return null;
}
