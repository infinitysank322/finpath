import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { API_BASE } from '../api-base';
import { LoanDetailComponent } from './loan-detail.component';
import { LoanDetailView } from './loan.models';
import { DETAIL_BULLET, DETAIL_CARD, DETAIL_EMI } from './loan.fixture';

describe('LoanDetailComponent', () => {
  let http: HttpTestingController;
  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]), provideHttpClient(), provideHttpClientTesting(), { provide: API_BASE, useValue: '/api' },
        { provide: ActivatedRoute, useValue: { paramMap: of(convertToParamMap({ id: 'abc' })) } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => http.verify());

  const render = (d: LoanDetailView) => {
    const f = TestBed.createComponent(LoanDetailComponent);
    f.detectChanges();
    http.expectOne('/api/loans/abc/detail').flush(d);
    f.detectChanges();
    return f.nativeElement as HTMLElement;
  };

  it('renders an emi loan with the labelled computed-EMI comparison', () => {
    const el = render(DETAIL_EMI);
    const t = el.textContent!;
    expect(t).toContain('EMI loan');
    expect(t).toContain('From your lender');
    expect(t).toContain('₹33,500');
    expect(t).toContain('comparison only');
    expect(t).toContain('₹33,462');
    expect(el.querySelectorAll('tbody tr').length).toBe(12);
    expect(el.querySelector('a[href="/debt/loans/loan-0/prepay"]')).not.toBeNull();
    (el.querySelector('button[aria-expanded]') as HTMLButtonElement).click();
  });

  it('renders a bullet loan with the balloon note and no comparison', () => {
    const el = render(DETAIL_BULLET);
    const t = el.textContent!;
    expect(t).toContain('Bullet loan');
    expect(t).toContain('Monthly interest');
    expect(t).toContain('includes the whole principal');
    expect(t).not.toContain('comparison only');
    expect(el.querySelector('a[href="/debt/loans/loan-1/sinking-fund"]')).not.toBeNull();
  });

  it('renders a card EMI loan', () => {
    const el = render(DETAIL_CARD);
    const t = el.textContent!;
    expect(t).toContain('Card EMI');
    expect(t).toContain('22.31% a year');
    expect(el.querySelectorAll('tbody tr').length).toBe(12);
    expect(el.querySelector('caption')!.textContent).toContain('Lender C');
  });

  it('flags a calculated EMI and has no comparison', () => {
    const el = render({ ...DETAIL_EMI, emiDerived: true, computedEmiComparison: null });
    expect(el.textContent).toContain('Calculated, not from lender');
  });

  it('shows an error for a missing loan', () => {
    const f = TestBed.createComponent(LoanDetailComponent);
    f.detectChanges();
    http.expectOne('/api/loans/abc/detail').flush({}, { status: 404, statusText: 'x' });
    f.detectChanges();
    expect((f.nativeElement as HTMLElement).querySelector('[role=alert]')!.textContent).toContain('no longer exists');
  });
});
