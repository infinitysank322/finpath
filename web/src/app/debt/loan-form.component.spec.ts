import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { API_BASE } from '../api-base';
import { LoanFormComponent } from './loan-form.component';
import { SAMPLE_LOANS, viewOf } from './loan.fixture';

describe('LoanFormComponent due day', () => {
  let http: HttpTestingController;
  let f: ComponentFixture<LoanFormComponent>;
  const root = () => f.nativeElement as HTMLElement;
  const type = (id: string, v: string) => {
    const i = root().querySelector<HTMLInputElement>('#' + id)!;
    i.value = v;
    i.dispatchEvent(new Event('input'));
    f.detectChanges();
  };
  const submit = () => root().querySelector<HTMLButtonElement>('button[type=submit]')!;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting(), { provide: API_BASE, useValue: '/api' }] });
    http = TestBed.inject(HttpTestingController);
    f = TestBed.createComponent(LoanFormComponent);
    f.detectChanges();
    type('ln-lender', 'Lender A');
    type('ln-principal', '1000');
    type('ln-rate', '8');
    type('ln-emi', '100');
    type('ln-left', '12');
  });
  afterEach(() => http.verify());

  it('has a label, helper text and a 44px-class numeric input', () => {
    const i = root().querySelector<HTMLInputElement>('#ln-dueday')!;
    expect(root().querySelector('label[for=ln-dueday]')!.textContent).toContain('Due day of month');
    expect(i.getAttribute('inputmode')).toBe('numeric');
    expect(root().textContent).toContain('Reminders arrive 5 days before. Leave empty for none.');
  });

  it('submits null when empty', () => {
    submit().click();
    const post = http.expectOne('/api/loans');
    expect(post.request.body.dueDay).toBeNull();
    post.flush(viewOf(SAMPLE_LOANS[0], 0));
  });

  it('submits the day as a number', () => {
    type('ln-dueday', '5');
    submit().click();
    const post = http.expectOne('/api/loans');
    expect(post.request.body.dueDay).toBe(5);
    post.flush({ ...viewOf(SAMPLE_LOANS[0], 0), dueDay: 5 });
  });

  for (const bad of ['0', '32', '5.5', 'x', '-1']) {
    it(`rejects "${bad}" with an error and does not submit`, () => {
      type('ln-dueday', bad);
      expect(root().textContent).toContain('Enter a day from 1 to 31');
      expect(submit().disabled).toBeTrue();
      root().querySelector('form')!.dispatchEvent(new Event('submit'));
      http.expectNone('/api/loans');
    });
  }

  it('accepts the boundaries 1 and 31', () => {
    for (const ok of ['1', '31']) {
      type('ln-dueday', ok);
      expect(root().textContent).not.toContain('Enter a day from 1 to 31');
      expect(submit().disabled).toBeFalse();
    }
  });
});
