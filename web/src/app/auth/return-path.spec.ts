import { safeReturnPath } from './return-path';

describe('safeReturnPath (open-redirect guard)', () => {
  it('keeps in-app paths, with query and hash', () => {
    expect(safeReturnPath('/spending/add')).toBe('/spending/add');
    expect(safeReturnPath('/debt/loans/7/prepay?x=1#top')).toBe('/debt/loans/7/prepay?x=1#top');
  });

  for (const bad of [
    'https://evil.example/',
    '//evil.example/x',
    '/\\evil.example',
    '\\\\evil.example',
    'javascript:alert(1)',
    'spending',
    '',
    '/auth/callback?code=x',
    '/auth/',
    '/sign-in',
    '/ok\nLocation: x',
    null,
    42,
  ]) {
    it(`rejects ${JSON.stringify(bad)} -> /home`, () => {
      expect(safeReturnPath(bad)).toBe('/home');
    });
  }
});
