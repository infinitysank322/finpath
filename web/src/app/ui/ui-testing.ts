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
