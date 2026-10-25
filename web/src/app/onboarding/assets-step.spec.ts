import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { API_BASE } from '../expense/expense-api.service';
import { AssetsStepComponent } from './assets-step.component';
import { ONBOARDING_STEP_PROVIDERS } from './onboarding.steps';
import { PlanStepsState } from './plan-steps.state';
import { ONBOARDING_STEP, OnboardingStep } from './wizard-step';

describe('assets step', () => {
  let http: HttpTestingController;
  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting(), { provide: API_BASE, useValue: '/api' }, ...ONBOARDING_STEP_PROVIDERS] });
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
  const button = (f: ComponentFixture<unknown>, text: string) => Array.from(el(f).querySelectorAll('button')).find((b) => b.textContent?.trim() === text)!;

  it('is registered after goals and before done, optional (no ready gate)', () => {
    const steps = (TestBed.inject(ONBOARDING_STEP) as unknown as OnboardingStep[]).sort((a, b) => a.order - b.order);
    expect(steps.map((s) => s.id)).toEqual(['consent', 'cycle-day', 'income', 'fixed-expenses', 'goals', 'assets', 'done']);
    expect(steps.find((s) => s.id === 'assets')!.ready).toBeUndefined();
  });

  it('asks for EPF with a default of 0 and no calculation', () => {
    const f = TestBed.createComponent(AssetsStepComponent);
    f.detectChanges();
    http.expectOne('/api/holdings').flush([]);
    f.detectChanges();
    expect(el(f).querySelector<HTMLInputElement>('#epf-balance')!.value).toBe('0');
    expect(el(f).textContent).toContain('We never work it out for you');
  });

  it('posts the typed EPF balance as paise', () => {
    const f = TestBed.createComponent(AssetsStepComponent);
    f.detectChanges();
    http.expectOne('/api/holdings').flush([]);
    type(f, 'epf-balance', '4,50,000');
    button(f, 'Save EPF balance').click();
    const post = http.expectOne('/api/holdings');
    expect(post.request.method).toBe('POST');
    expect(post.request.body).toEqual({ assetClass: 'epf', name: 'EPF', investedPaise: 0, currentValuePaise: 45000000, monthlyContributionPaise: 0 });
    post.flush({});
    const row = { id: 'e1', ...post.request.body };
    http.expectOne('/api/holdings').flush([row]);
    f.detectChanges();
    expect(TestBed.inject(PlanStepsState).holdingCount()).toBe(1);
    expect(button(f, 'Update EPF balance')).toBeDefined();
  });

  it('updates the existing EPF holding instead of adding a second one', () => {
    const f = TestBed.createComponent(AssetsStepComponent);
    f.detectChanges();
    http.expectOne('/api/holdings').flush([{ id: 'e1', assetClass: 'epf', name: 'Employer EPF', investedPaise: 0, currentValuePaise: 100000, monthlyContributionPaise: 5000 }]);
    f.detectChanges();
    expect(el(f).querySelector<HTMLInputElement>('#epf-balance')!.value).toBe('1000');
    type(f, 'epf-balance', '2000');
    button(f, 'Update EPF balance').click();
    const put = http.expectOne('/api/holdings/e1');
    expect(put.request.method).toBe('PUT');
    expect(put.request.body).toEqual({ assetClass: 'epf', name: 'Employer EPF', investedPaise: 0, currentValuePaise: 200000, monthlyContributionPaise: 5000 });
    put.flush({});
    http.expectOne('/api/holdings').flush([]);
  });

  it('rejects a malformed EPF amount', () => {
    const f = TestBed.createComponent(AssetsStepComponent);
    f.detectChanges();
    http.expectOne('/api/holdings').flush([]);
    type(f, 'epf-balance', '12x');
    expect(button(f, 'Save EPF balance').disabled).toBeTrue();
  });

  it('adds another holding with a label and current value, never offering EPF twice or an ID field', () => {
    const f = TestBed.createComponent(AssetsStepComponent);
    f.detectChanges();
    http.expectOne('/api/holdings').flush([]);
    expect(Array.from(el(f).querySelectorAll('#asset-class option')).map((o) => (o as HTMLOptionElement).value)).toEqual(['ppf', 'nps', 'mf', 'stocks', 'fd', 'gold']);
    expect(el(f).textContent).toContain('Do NOT enter account, folio, demat, PAN or UAN numbers');
    expect(button(f, 'Add holding').disabled).toBeTrue();
    type(f, 'asset-name', 'Index fund');
    type(f, 'asset-value', '1,00,000');
    button(f, 'Add holding').click();
    const post = http.expectOne('/api/holdings');
    expect(post.request.body).toEqual({ assetClass: 'mf', name: 'Index fund', investedPaise: 0, currentValuePaise: 10000000, monthlyContributionPaise: 0 });
    post.flush({});
    http.expectOne('/api/holdings').flush([{ id: 'h1', ...post.request.body }]);
    f.detectChanges();
    expect(el(f).querySelector('.rows')!.textContent).toContain('₹1,00,000');
  });
});
