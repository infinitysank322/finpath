/** RFC 7636 PKCE (S256) and OAuth state helpers. Needs a secure context (localhost and https both are). */

export function base64url(bytes: Uint8Array): string {
  let bin = '';
  for (const b of bytes) bin += String.fromCharCode(b);
  return btoa(bin).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

function randomBytes(n: number): Uint8Array {
  const out = new Uint8Array(n);
  crypto.getRandomValues(out);
  return out;
}

/** 32 random bytes -> 43 base64url chars, inside RFC 7636's 43-128 range. */
export function createVerifier(): string {
  return base64url(randomBytes(32));
}

/** 16 random bytes -> 22 chars of [A-Za-z0-9_-], which the API's state pattern [A-Za-z0-9._~-]{1,128} accepts. */
export function createState(): string {
  return base64url(randomBytes(16));
}

/** code_challenge = BASE64URL(SHA-256(ASCII(verifier))). */
export async function challengeFor(verifier: string): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(verifier));
  return base64url(new Uint8Array(digest));
}
