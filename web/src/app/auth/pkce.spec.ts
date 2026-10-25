import { base64url, challengeFor, createState, createVerifier } from './pkce';

describe('PKCE and state', () => {
  it('derives the RFC 7636 appendix B challenge from its verifier', async () => {
    expect(await challengeFor('dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk')).toBe(
      'E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM',
    );
  });

  it('makes a 43-char base64url verifier whose challenge is also 43 chars, fresh every time', async () => {
    const a = createVerifier();
    const b = createVerifier();
    expect(a).toMatch(/^[A-Za-z0-9_-]{43}$/);
    expect(a).not.toBe(b);
    expect(await challengeFor(a)).toMatch(/^[A-Za-z0-9_-]{43}$/);
  });

  it('makes a state the API accepts ([A-Za-z0-9._~-]{1,128}) from at least 16 random bytes', () => {
    const s = createState();
    expect(s).toMatch(/^[A-Za-z0-9._~-]{1,128}$/);
    expect(s.length).toBeGreaterThanOrEqual(22); // 16 bytes = 22 base64url chars
    expect(createState()).not.toBe(s);
  });

  it('base64url has no padding or +/', () => {
    expect(base64url(new Uint8Array([0xfb, 0xff, 0xfe]))).toBe('-__-');
    expect(base64url(new Uint8Array([1]))).toBe('AQ');
  });
});
