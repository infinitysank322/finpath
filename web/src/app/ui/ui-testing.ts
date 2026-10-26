import { ComponentFixture } from '@angular/core/testing';

/** WCAG 2.5.5 target size: the element's own box must be at least 44 x 44 CSS px. */
export function expectTarget44(el: Element, context = ''): void {
  const r = el.getBoundingClientRect();
  expect(r.height).withContext(`${context} height`).toBeGreaterThanOrEqual(44);
  expect(r.width).withContext(`${context} width`).toBeGreaterThanOrEqual(44);
}

/** Set several signal inputs at once, then render. */
export function setInputs<T>(fixture: ComponentFixture<T>, inputs: Record<string, unknown>): void {
  for (const [k, v] of Object.entries(inputs)) fixture.componentRef.setInput(k, v);
  fixture.detectChanges();
}

/** The phone width every screen must fit (T06b): no horizontal scroll in dark or light. */
export function expectFitsPhone(el: HTMLElement, context = ''): void {
  const host = document.createElement('div');
  host.style.width = '380px';
  host.appendChild(el);
  document.body.appendChild(host);
  try {
    for (const theme of ['dark', 'light']) {
      document.documentElement.dataset['theme'] = theme;
      expect(host.scrollWidth).withContext(`${context} ${theme} host`).toBeLessThanOrEqual(380);
      expect(el.scrollWidth).withContext(`${context} ${theme} content`).toBeLessThanOrEqual(380);
    }
  } finally {
    delete document.documentElement.dataset['theme'];
    host.remove();
  }
}
