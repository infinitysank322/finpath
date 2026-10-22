// Text pairs need 4.5:1 (WCAG 1.4.3); border/focus are non-text UI and need 3:1 (WCAG 1.4.11).
const TEXT_ON_BG: [string, string][] = [
  ['--fp-text', '--fp-bg'],
  ['--fp-text', '--fp-surface'],
  ['--fp-text-muted', '--fp-bg'],
  ['--fp-text-muted', '--fp-surface'],
  ['--fp-primary-text', '--fp-bg'],
  ['--fp-primary-text', '--fp-surface'],
  ['--fp-on-primary', '--fp-primary'],
  ['--fp-warn-text', '--fp-warn-bg'],
  ['--fp-breach-text', '--fp-breach-bg'],
];
const NON_TEXT_ON_BG: [string, string][] = [
  ['--fp-border-strong', '--fp-bg'],
  ['--fp-border-strong', '--fp-surface'],
  ['--fp-focus', '--fp-bg'],
  ['--fp-focus', '--fp-surface'],
  ['--fp-warn-bar', '--fp-bg'],
  ['--fp-breach-bar', '--fp-bg'],
  ['--fp-warn-bar', '--fp-surface'],
  ['--fp-breach-bar', '--fp-surface'],
];

function channel(c: number): number {
  const s = c / 255;
  return s <= 0.03928 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
}

function luminance(hex: string): number {
  const h = hex.trim().replace('#', '');
  const [r, g, b] = [0, 2, 4].map((i) => parseInt(h.slice(i, i + 2), 16));
  return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b);
}

function ratio(a: string, b: string): number {
  const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x);
  return (hi + 0.05) / (lo + 0.05);
}

describe('design token contrast', () => {
  afterEach(() => document.documentElement.removeAttribute('data-theme'));

  for (const theme of ['light', 'dark']) {
    describe(theme, () => {
      for (const [pairs, min] of [[TEXT_ON_BG, 4.5], [NON_TEXT_ON_BG, 3]] as const) {
        for (const [fg, bg] of pairs) {
          it(`${fg} on ${bg} >= ${min}:1`, () => {
            document.documentElement.dataset['theme'] = theme;
            const css = getComputedStyle(document.documentElement);
            const f = css.getPropertyValue(fg);
            const b = css.getPropertyValue(bg);
            expect(f.trim()).toMatch(/^#[0-9a-f]{6}$/i);
            expect(b.trim()).toMatch(/^#[0-9a-f]{6}$/i);
            expect(ratio(f, b)).toBeGreaterThanOrEqual(min);
          });
        }
      }
    });
  }
});
