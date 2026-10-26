import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { FpProfileLinkComponent } from './fp-profile-link.component';
import { expectTarget44 } from './ui-testing';

describe('FpProfileLinkComponent', () => {
  it('is a real link to /more named "More", at least 44px, with no hard-coded name', () => {
    TestBed.configureTestingModule({ providers: [provideRouter([])] });
    const f = TestBed.createComponent(FpProfileLinkComponent);
    f.detectChanges();
    const a = f.nativeElement.querySelector('a') as HTMLAnchorElement;
    expect(a.getAttribute('href')).toBe('/more');
    expect(a.getAttribute('aria-label')).toBe('More');
    expect(a.textContent?.trim()).toBe('');
    expectTarget44(a, 'profile link');
  });
});
