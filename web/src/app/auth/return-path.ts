export const DEFAULT_RETURN_PATH = '/home';

const CONTROL_CHARS = /[\u0000-\u001f\u007f]/;

/**
 * Only an in-app path may be returned to after sign-in: starts with a single '/', no backslash or control
 * characters, stays on this origin, and is not an auth screen. Anything else becomes /home.
 */
export function safeReturnPath(p: unknown): string {
  if (typeof p !== 'string' || p.length === 0 || p.length > 2048) return DEFAULT_RETURN_PATH;
  if (!p.startsWith('/') || p.startsWith('//') || p.includes('\\')) return DEFAULT_RETURN_PATH;
  if (CONTROL_CHARS.test(p)) return DEFAULT_RETURN_PATH;
  let url: URL;
  try {
    url = new URL(p, 'https://finpath.invalid');
  } catch {
    return DEFAULT_RETURN_PATH;
  }
  if (url.origin !== 'https://finpath.invalid') return DEFAULT_RETURN_PATH;
  const path = url.pathname;
  if (path === '/auth' || path.startsWith('/auth/') || path === '/sign-in' || path.startsWith('/sign-in/')) {
    return DEFAULT_RETURN_PATH;
  }
  return path + url.search + url.hash;
}
