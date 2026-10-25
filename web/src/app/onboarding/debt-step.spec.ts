import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { API_BASE } from '../api-base';
import { LoanInput } from '../debt/loan.models';
import { SAMPLE_EMI_TOTAL_PAISE, SAMPLE_LOANS, viewOf } from '../debt/loan.fixture';
import { rateToPctText } from '../debt/loan-format';
import { paiseToRupeesText } from '../plan/plan-format';
import { DebtStepComponent } from './debt-step.component';
import { PlanStepsState } from './plan-steps.state';

describe('onboarding debt step', () => {
  let http: HttpTestingController;
  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting(), { provide: API_BASE, useValue: '/api' }],
    });
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => http.verify());

  const el = (f: ComponentFixture<unknown>) => f.nativeElement as HTMLElement;
  const type = (f: ComponentFixture<unknown>, id: string, value: string) => {
    const i = el(f).querySelector<HTMLInputElement>('#' + id)!;
    i.value = value;
    i.dispatchEvent(new Event('input'));
    f.detectChanges();
  };
  const choose = (f: ComponentFixture<unknown>, value: string) => {
    const s = el(f).querySelector<HTMLSelectElement>('#ln-type')!;
    s.value = value;
    s.dispatchEvent(new Event('change'));
    f.detectChanges();
  };
  const submit = (f: ComponentFixture<unknown>) => el(f).querySelector<HTMLButtonElement>('button[type=submit]')!;
  const fill = (f: ComponentFixture<unknown>, l: LoanInput) => {
    choose(f, l.type);
    type(f, 'ln-lender', l.lender);
    type(f, 'ln-principal', paiseToRupeesText(l.principalOutstandingPaise));
    type(f, 'ln-rate', rateToPctText(l.rate));
    if (l.emiPaise !== null) type(f, 'ln-emi', paiseToRupeesText(l.emiPaise));
    if (l.bulletDueDate) type(f, 'ln-due', l.bulletDueDate);
    if (l.emisLeft !== null) type(f, 'ln-left', String(l.emisLeft));
    if (l.upfrontFeePaise) type(f, 'ln-fee', paiseToRupeesText(l.upfrontFeePaise));
  };

  it('enters the five sample loans and shows the server total EMI', () => {
    const f = TestBed.createComponent(DebtStepComponent);
    f.detectChanges();
    http.expectOne('/api/loans').flush([]);
    http.expectOne('/api/loans/debt-trap-guard').flush({ ratio: null, status: 'OK', headroomPaise: 0, totalEmiPaise: 0, monthlyIncomePaise: 0 });
    expect(TestBed.inject(PlanStepsState).loanCount()).toBe(0);

    SAMPLE_LOANS.forEach((loan, n) => {
      fill(f, loan);
      expect(submit(f).disabled).toBeFalse();
      submit(f).click();
      const post = http.expectOne((r) => r.method === 'POST' && r.url === '/api/loans');
      expect(post.request.params.has('confirmBlock')).toBeFalse();
      expect(post.request.body).toEqual(loan);
      post.flush(viewOf(loan, n));
      http.expectOne('/api/loans').flush(SAMPLE_LOANS.slice(0, n + 1).map(viewOf));
      const emiSoFar = SAMPLE_LOANS.slice(0, n + 1).reduce((a, x) => a + (x.emiPaise ?? 0), 0);
      http.expectOne('/api/loans/debt-trap-guard').flush({ ratio: '0.4', status: 'OK', headroomPaise: 0, totalEmiPaise: emiSoFar, monthlyIncomePaise: 22_222_200 });
      f.detectChanges();
    });

    expect(el(f).querySelectorAll('[aria-label="Loans added"] li').length).toBe(5);
    expect(TestBed.inject(PlanStepsState).loanCount()).toBe(5);
    expect(SAMPLE_LOANS.reduce((a, x) => a + (x.emiPaise ?? 0), 0)).toBe(SAMPLE_EMI_TOTAL_PAISE);
    expect(el(f).querySelector('[role=status]')!.textContent).toContain('₹88,888');
  });

  it('keeps the form disabled until it is complete and never asks for an account number', () => {
    const f = TestBed.createComponent(DebtStepComponent);
    f.detectChanges();
    http.expectOne('/api/loans').flush([]);
    http.expectOne('/api/loans/debt-trap-guard').flush({ ratio: null, status: 'OK', headroomPaise: 0, totalEmiPaise: 0, monthlyIncomePaise: 0 });
    expect(submit(f).disabled).toBeTrue();
    choose(f, 'card_emi');
    type(f, 'ln-lender', 'Lender X');
    type(f, 'ln-principal', '1000');
    type(f, 'ln-rate', '12');
    type(f, 'ln-left', '6');
    expect(submit(f).disabled).toBeTrue(); // card EMI needs the stated instalment
    type(f, 'ln-emi', '200');
    expect(submit(f).disabled).toBeFalse();
    expect(el(f).textContent).not.toMatch(/account number is|card number is/i);
    expect(el(f).querySelectorAll('input[type=text]').length).toBeLessThan(8);
  });

  it('re-sends with confirmBlock only after the user confirms a blocked loan', () => {
    const f = TestBed.createComponent(DebtStepComponent);
    f.detectChanges();
    http.expectOne('/api/loans').flush([]);
    http.expectOne('/api/loans/debt-trap-guard').flush({ ratio: null, status: 'OK', headroomPaise: 0, totalEmiPaise: 0, monthlyIncomePaise: 0 });
    fill(f, SAMPLE_LOANS[0]);
    submit(f).click();
    http.expectOne('/api/loans').flush(
      { status: 409, error: 'debt_trap_blocked', guard: { ratio: '0.51', status: 'BLOCK', headroomPaise: 0, totalEmiPaise: 1, monthlyIncomePaise: 2 } },
      { status: 409, statusText: 'Conflict' },
    );
    f.detectChanges();
    const dlg = el(f).querySelector('dialog')!;
    expect(dlg.open).toBeTrue();
    (dlg.querySelector('[data-testid=proceed]') as HTMLButtonElement).click();
    const again = http.expectOne((r) => r.url === '/api/loans');
    expect(again.request.params.get('confirmBlock')).toBe('true');
    again.flush(viewOf(SAMPLE_LOANS[0], 0));
    http.expectOne('/api/loans').flush([viewOf(SAMPLE_LOANS[0], 0)]);
    http.expectOne('/api/loans/debt-trap-guard').flush({ ratio: '0.2', status: 'OK', headroomPaise: 0, totalEmiPaise: 3_350_000, monthlyIncomePaise: 1 });
  });

  it('clears the total when the guard call fails after an add', () => {
    const f = TestBed.createComponent(DebtStepComponent);
    f.detectChanges();
    http.expectOne('/api/loans').flush([viewOf(SAMPLE_LOANS[0], 0)]);
    http.expectOne('/api/loans/debt-trap-guard').flush({ ratio: '0.2', status: 'OK', headroomPaise: 0, totalEmiPaise: 3_350_000, monthlyIncomePaise: 1 });
    expect(f.componentInstance.totalEmiPaise()).toBe(3_350_000);
    f.componentInstance.added();
    http.expectOne('/api/loans').flush([viewOf(SAMPLE_LOANS[0], 0)]);
    http.expectOne('/api/loans/debt-trap-guard').flush({}, { status: 500, statusText: 'x' });
    expect(f.componentInstance.totalEmiPaise()).toBeNull();
  });
});
