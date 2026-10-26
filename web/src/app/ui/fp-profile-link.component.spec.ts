import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
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

  it('a plain click navigates in-app to /more; a ctrl-click is left to the browser', () => {
    TestBed.configureTestingModule({ providers: [provideRouter([])] });
    const nav = spyOn(TestBed.inject(Router), 'navigateByUrl').and.resolveTo(true);
    const f = TestBed.createComponent(FpProfileLinkComponent);
    f.detectChanges();
    const a = f.nativeElement.querySelector('a') as HTMLAnchorElement;
    a.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true, ctrlKey: true }));
    expect(nav).not.toHaveBeenCalled();
    a.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }));
    expect(nav).toHaveBeenCalledOnceWith('/more');
  });
});
