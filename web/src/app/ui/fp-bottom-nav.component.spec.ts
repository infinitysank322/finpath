import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { FpBottomNavComponent } from './fp-bottom-nav.component';
import { expectTarget44 } from './ui-testing';

describe('FpBottomNavComponent', () => {
  async function make(url = '/home') {
    TestBed.configureTestingModule({
      providers: [provideRouter([{ path: '**', children: [] }])],
    });
    const router = TestBed.inject(Router);
    await router.navigateByUrl(url);
    const f = TestBed.createComponent(FpBottomNavComponent);
    f.detectChanges();
    const links = () => Array.from(f.nativeElement.querySelectorAll('a')) as HTMLAnchorElement[];
    const current = () => links().filter((a) => a.getAttribute('aria-current') === 'page').map((a) => a.textContent?.trim());
    return { f, router, links, current };
  }

  it('is a labelled nav with Home, Spend, central Add expense, Debt, Goals in order, with visible labels', async () => {
    const { f, links } = await make();
    expect(f.nativeElement.querySelector('nav').getAttribute('aria-label')).toBe('Primary');
    expect(links().map((a) => a.getAttribute('aria-label') ?? a.textContent?.trim())).toEqual(['Home', 'Spend', 'Add expense', 'Debt', 'Goals']);
    expect(links().map((a) => a.getAttribute('href'))).toEqual(['/home', '/spending', '/spending/add', '/debt', '/goals']);
    const add = f.nativeElement.querySelector('a.add') as HTMLElement;
    expect(add.textContent?.trim()).toBe('');
    expect(add.getAttribute('aria-label')).toBe('Add expense');
  });

  it('marks exactly the current destination with aria-current="page"', async () => {
    const { current, f, router } = await make('/home');
    expect(current()).toEqual(['Home']);
    await router.navigateByUrl('/debt/payoff');
    f.detectChanges();
    expect(current()).toEqual(['Debt']);
    await router.navigateByUrl('/spending/budget');
    f.detectChanges();
    expect(current()).toEqual(['Spend']);
  });

  it('the Add expense page lights up no tab (the + button is a shortcut, not a destination)', async () => {
    const { current } = await make('/spending/add');
    expect(current()).toEqual([]);
  });

  it('is fixed to the bottom and every target is at least 44px', async () => {
    const { f, links } = await make();
    expect(getComputedStyle(f.nativeElement).position).toBe('fixed');
    for (const a of links()) expectTarget44(a, a.getAttribute('aria-label') ?? a.textContent ?? '');
  });

  it('is keyboard reachable: links are in the tab order', async () => {
    const { links } = await make();
    for (const a of links()) expect(a.tabIndex).toBe(0);
  });

  it('icons are decorative', async () => {
    const { f } = await make();
    for (const s of Array.from(f.nativeElement.querySelectorAll('svg')) as SVGElement[]) expect(s.getAttribute('aria-hidden')).toBe('true');
  });
});
