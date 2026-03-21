import { buildAuthSession, decodeJwtClaims, parseRoles } from '@/utils/jwt';
import type { TokenResponse } from '@/types/auth';
import { createJwtToken } from '@/test/helpers';

describe('jwt utils', () => {
  const token = createJwtToken({
    sub: 'alice',
    uid: 7,
    username: 'alice',
    roles: 'ADMIN,USER',
    iat: 1_710_000_000,
    exp: 1_710_007_200,
  });

  test('decodes claims from jwt payload', () => {
    const claims = decodeJwtClaims(token);

    expect(claims.username).toBe('alice');
    expect(claims.uid).toBe(7);
  });

  test('parses roles from comma separated string', () => {
    expect(parseRoles('ADMIN,USER')).toEqual(['ADMIN', 'USER']);
    expect(parseRoles('USER, ,ADMIN')).toEqual(['USER', 'ADMIN']);
  });

  test('builds auth session from token response', () => {
    const response: TokenResponse = {
      accessToken: token,
      tokenType: 'Bearer',
      expiresIn: 7200,
    };

    const session = buildAuthSession(response, { now: 1_710_000_000_000 });

    expect(session.userId).toBe(7);
    expect(session.username).toBe('alice');
    expect(session.roles).toEqual(['ADMIN', 'USER']);
    expect(session.expireAt).toBe(1_710_007_200_000);
  });
});
