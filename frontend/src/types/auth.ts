export interface LoginRequest {
  username: string;
  password: string;
}

export interface RegisterRequest {
  username: string;
  password: string;
}

export interface TokenResponse {
  accessToken: string;
  tokenType: 'Bearer';
  expiresIn: number;
}

export interface JwtClaims {
  sub: string;
  uid: number;
  username: string;
  roles: string;
  iat: number;
  exp: number;
}

export type RoleCode = 'ADMIN' | 'USER';
export type AuthRestoreStatus = 'restoring' | 'authenticated' | 'logged_out';

export interface AuthSession {
  userId: number;
  username: string;
  roles: RoleCode[];
  accessToken: string;
  tokenType: 'Bearer';
  expiresIn: number;
  expireAt: number;
  jwtExp: number;
}

export interface TokenSnapshot {
  accessToken: string;
  tokenType: string;
  expireAt: number;
}
