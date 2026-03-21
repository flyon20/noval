import { resolveAuthRedirect } from '@/router/guards';

describe('route guards', () => {
  test('redirects unauthenticated users from protected routes', () => {
    expect(
      resolveAuthRedirect(
        {
          path: '/rank',
          meta: {},
        },
        false,
      ),
    ).toBe('/login');
  });

  test('redirects authenticated users away from login page', () => {
    expect(
      resolveAuthRedirect(
        {
          path: '/login',
          meta: {
            public: true,
          },
        },
        true,
      ),
    ).toBe('/rank');
  });

  test('allows authenticated users to access protected routes', () => {
    expect(
      resolveAuthRedirect(
        {
          path: '/rank',
          meta: {},
        },
        true,
      ),
    ).toBeNull();
  });
});
