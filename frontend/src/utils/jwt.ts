import type { AuthSession, JwtClaims, RoleCode, TokenResponse, TokenSnapshot } from '@/types/auth';

function decodeBase64Url(value: string) {
  const normalized = value.replace(/-/g, '+').replace(/_/g, '/');
  const padded = normalized.padEnd(Math.ceil(normalized.length / 4) * 4, '=');
  const decoded = globalThis.atob(padded);

  return decodeURIComponent(
    Array.from(decoded)
      .map((char) => `%${char.charCodeAt(0).toString(16).padStart(2, '0')}`)
      .join(''),
  );
}

export function parseRoles(value: string): RoleCode[] {
  return value
    .split(',')
    .map((item) => item.trim())
    .filter(Boolean) as RoleCode[];
}

export function decodeJwtClaims(token: string): JwtClaims {
  const payload = token.split('.')[1];

  if (!payload) {
    throw new Error('Invalid JWT token');
  }

  return JSON.parse(decodeBase64Url(payload)) as JwtClaims;
}

export function buildAuthSession(
  response: TokenResponse,
  options: {
    now?: number;
  } = {},
): AuthSession {
  const now = options.now ?? Date.now();
  const claims = decodeJwtClaims(response.accessToken);

  return {
    userId: claims.uid,
    username: claims.username,
    roles: parseRoles(claims.roles),
    accessToken: response.accessToken,
    tokenType: response.tokenType,
    expiresIn: response.expiresIn,
    expireAt: now + response.expiresIn * 1000,
    jwtExp: claims.exp,
  };
}

export function buildAuthSessionFromSnapshot(
  snapshot: TokenSnapshot,
  options: {
    now?: number;
  } = {},
): AuthSession | null {
  const now = options.now ?? Date.now();
  const claims = decodeJwtClaims(snapshot.accessToken);
  const expireAt = snapshot.expireAt || claims.exp * 1000;

  if (expireAt <= now) {
    return null;
  }

  return {
    userId: claims.uid,
    username: claims.username,
    roles: parseRoles(claims.roles),
    accessToken: snapshot.accessToken,
    tokenType: snapshot.tokenType as 'Bearer',
    expiresIn: Math.max(0, Math.floor((expireAt - now) / 1000)),
    expireAt,
    jwtExp: claims.exp,
  };
}
