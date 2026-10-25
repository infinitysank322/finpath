import { TokenResponse } from './session.store';

const b64url = (s: string) => btoa(s).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');

/** Test helper: an unsigned JWT-shaped string with the given `sub` (the client never verifies it). */
export function fakeJwt(sub: string, extra: Record<string, unknown> = {}): string {
  return `${b64url(JSON.stringify({ alg: 'HS256' }))}.${b64url(JSON.stringify({ sub, iss: 'finpath', ...extra }))}.sig`;
}

export function tokenResponse(sub: string, refreshToken: string, expiresIn = 900, tag = ''): TokenResponse {
  return { accessToken: fakeJwt(sub, tag ? { jti: tag } : {}), tokenType: 'Bearer', expiresIn, refreshToken };
}

export const USER_A = '6f1c2a7e-1b2c-4d5e-8f90-0a1b2c3d4e5f';
export const USER_B = '0b9e8d7c-6b5a-4f3e-9d2c-1b0a9f8e7d6c';
