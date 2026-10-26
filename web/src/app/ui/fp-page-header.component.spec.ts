import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { FpPageHeaderComponent } from './fp-page-header.component';

@Component({
  standalone: true,
  imports: [FpPageHeaderComponent],
  template: `
    <fp-page-header title="Screen" eyebrow="Overview">
      <button fpLeading type="button" aria-label="Back">b</button>
      <button fpTrailing type="button" aria-label="Extra">x</button>
    </fp-page-header>
    <fp-page-header title="Bare" />
  `,
})
class HostComponent {}

describe('FpPageHeaderComponent', () => {
  function make() {
    const f = TestBed.createComponent(HostComponent);
    f.detectChanges();
    return f.nativeElement as HTMLElement;
  }

  it('renders one h1 title in the number font and the eyebrow as a section label', () => {
    const header = make().querySelector('fp-page-header') as HTMLElement;
    const h1 = header.querySelector('h1') as HTMLElement;
    expect(h1.textContent).toBe('Screen');
    expect(h1.classList).toContain('fp-num');
    expect(getComputedStyle(h1).fontFamily).toContain('Space Grotesk');
    expect(header.querySelector('.fp-label')?.textContent).toBe('Overview');
  });

  it('omits the eyebrow when not given and projects leading / trailing slots around the title', () => {
    const el = make();
    expect(el.querySelectorAll('fp-page-header')[1].querySelector('.fp-label')).toBeNull();
    const kids = Array.from(el.querySelector('fp-page-header')!.children).map((c) => c.getAttribute('aria-label') ?? c.className);
    expect(kids).toEqual(['Back', 'main', 'Extra']);
  });
});
