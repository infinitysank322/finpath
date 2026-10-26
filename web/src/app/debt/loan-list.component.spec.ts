import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { API_BASE } from '../api-base';
import { LoanListComponent } from './loan-list.component';
import { rupees, SAMPLE_EMI_TOTAL_PAISE, SAMPLE_VIEWS } from './loan.fixture';

describe('LoanListComponent', () => {
  const guardBody = (n: number) => ({ ratio: '0.4', status: 'OK', headroomPaise: 0, totalEmiPaise: n, monthlyIncomePaise: 1 });
  let http: HttpTestingController;
  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting(), { provide: API_BASE, useValue: '/api' }],
    });
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => http.verify());

  it('lists all three loan types and shows the server total EMI', () => {
    const f = TestBed.createComponent(LoanListComponent);
    f.detectChanges();
    http.expectOne('/api/loans').flush(SAMPLE_VIEWS);
    http.expectOne('/api/loans/debt-trap-guard').flush({ ratio: '0.4', status: 'OK', headroomPaise: 0, totalEmiPaise: SAMPLE_EMI_TOTAL_PAISE, monthlyIncomePaise: 1_234_500 });
    f.detectChanges();
    const el = f.nativeElement as HTMLElement;
    expect(el.querySelectorAll('ul[aria-label=Loans] > li').length).toBe(5);
    const text = el.textContent!;
    for (const label of ['EMI loan', 'Bullet loan', 'Card EMI']) expect(text).toContain(label);
    expect(el.querySelector('.total')!.textContent).toContain(rupees(SAMPLE_EMI_TOTAL_PAISE));
    expect(el.querySelector('a.card-link')!.getAttribute('href')).toBe('/debt/loans/loan-0');
    expect(text).toContain('Monthly interest');
    expect(text).not.toMatch(/account number/i);
  });

  it('flags a calculated EMI', () => {
    const f = TestBed.createComponent(LoanListComponent);
    f.detectChanges();
    http.expectOne('/api/loans').flush([{ ...SAMPLE_VIEWS[0], emiDerived: true }]);
    http.expectOne('/api/loans/debt-trap-guard').flush({ ratio: null, status: 'OK', headroomPaise: 0, totalEmiPaise: 0, monthlyIncomePaise: 0 });
    f.detectChanges();
    expect((f.nativeElement as HTMLElement).textContent).toContain('Calculated, not from lender');
  });

  it('shows the due day only when set', () => {
    const f = TestBed.createComponent(LoanListComponent);
    f.detectChanges();
    http.expectOne('/api/loans').flush([{ ...SAMPLE_VIEWS[0], dueDay: 2 }, SAMPLE_VIEWS[3]]);
    http.expectOne('/api/loans/debt-trap-guard').flush(guardBody(1));
    f.detectChanges();
    const cards = (f.nativeElement as HTMLElement).querySelectorAll('ul[aria-label=Loans] > li');
    expect(cards[0].textContent).toContain('the 2nd');
    expect(cards[1].textContent).not.toContain('Due on');
  });

  it('shows an error with retry when loading fails', () => {
    const f = TestBed.createComponent(LoanListComponent);
    f.detectChanges();
    http.expectOne('/api/loans').flush({}, { status: 500, statusText: 'x' });
    http.match('/api/loans/debt-trap-guard');
    f.detectChanges();
    expect((f.nativeElement as HTMLElement).querySelector('[role=alert]')!.textContent).toContain('server had a problem');
  });

  it('asks for confirmation before deleting and disables Yes while the DELETE is pending', () => {
    const f = TestBed.createComponent(LoanListComponent);
    f.detectChanges();
    http.expectOne('/api/loans').flush(SAMPLE_VIEWS);
    http.expectOne('/api/loans/debt-trap-guard').flush(guardBody(SAMPLE_EMI_TOTAL_PAISE));
    f.detectChanges();
    const el = f.nativeElement as HTMLElement;
    const btn = (label: string) => el.querySelector<HTMLButtonElement>(`button[aria-label="${label}"]`)!;
    btn('Remove Lender A').click();
    f.detectChanges();
    http.expectNone('/api/loans/loan-0');
    expect(el.textContent).toContain('Remove?');
    btn('Yes, remove Lender A').click();
    f.detectChanges();
    expect(btn('Yes, remove Lender A').disabled).toBeTrue();
    btn('Yes, remove Lender A').click(); // ignored while pending
    http.expectOne('/api/loans/loan-0').flush(null, { status: 204, statusText: 'No Content' });
    http.expectOne('/api/loans').flush(SAMPLE_VIEWS.slice(1));
    http.expectOne('/api/loans/debt-trap-guard').flush(guardBody(1));
  });

  it('No cancels the confirmation without deleting', () => {
    const f = TestBed.createComponent(LoanListComponent);
    f.detectChanges();
    http.expectOne('/api/loans').flush(SAMPLE_VIEWS);
    http.expectOne('/api/loans/debt-trap-guard').flush(guardBody(1));
    f.detectChanges();
    const el = f.nativeElement as HTMLElement;
    el.querySelector<HTMLButtonElement>('button[aria-label="Remove Lender A"]')!.click();
    f.detectChanges();
    Array.from(el.querySelectorAll('button')).find((b) => b.textContent?.trim() === 'No')!.click();
    f.detectChanges();
    expect(el.textContent).not.toContain('Remove?');
  });

  it('drops the total when the guard call fails on reload (no stale total)', () => {
    const f = TestBed.createComponent(LoanListComponent);
    f.detectChanges();
    http.expectOne('/api/loans').flush(SAMPLE_VIEWS);
    http.expectOne('/api/loans/debt-trap-guard').flush(guardBody(SAMPLE_EMI_TOTAL_PAISE));
    f.detectChanges();
    expect(f.componentInstance.totalEmiPaise()).toBe(SAMPLE_EMI_TOTAL_PAISE);
    f.componentInstance.load();
    http.expectOne('/api/loans').flush(SAMPLE_VIEWS);
    http.expectOne('/api/loans/debt-trap-guard').flush({}, { status: 500, statusText: 'x' });
    f.detectChanges();
    expect(f.componentInstance.totalEmiPaise()).toBeNull();
    expect((f.nativeElement as HTMLElement).querySelector('.total')).toBeNull();
  });

  it('only the latest of two overlapping reloads wins', () => {
    const f = TestBed.createComponent(LoanListComponent);
    f.detectChanges();
    const first = http.expectOne('/api/loans');
    http.expectOne('/api/loans/debt-trap-guard');
    f.componentInstance.load();
    expect(first.cancelled).toBeTrue();
    http.expectOne('/api/loans').flush(SAMPLE_VIEWS.slice(0, 1));
    http.expectOne('/api/loans/debt-trap-guard').flush(guardBody(3_350_000));
    expect(f.componentInstance.loans().length).toBe(1);
    expect(f.componentInstance.totalEmiPaise()).toBe(3_350_000);
  });
});
