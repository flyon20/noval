export interface LoginRequest {
  phone: string;
  password: string;
}

export interface RegisterRequest {
  phone: string;
  smsCode: string;
  smsOutId?: string;
  password: string;
}

export interface SmsLoginRequest {
  phone: string;
  smsCode: string;
  smsOutId?: string;
  deviceLabel?: string;
}

export interface SmsSendRequest {
  phone: string;
  bizType: 'REGISTER' | 'LOGIN' | 'RESET_PASSWORD';
  turnstileToken?: string;
}

export interface SmsSendResponse {
  debugVerifyCode?: string | null;
  smsOutId?: string | null;
}

export interface PasswordResetRequest {
  phone: string;
  smsCode: string;
  smsOutId?: string;
  newPassword: string;
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
  phone?: string;
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
