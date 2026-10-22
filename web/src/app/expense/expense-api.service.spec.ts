import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed, fakeAsync, tick } from '@angular/core/testing';
import { CREATE_TIMEOUT_MS, ExpenseApiService } from './expense-api.service';

describe('ExpenseApiService.create', () => {
  const input = { txnDate: '2026-10-22', amountPaise: 500, categoryCode: 'DINING', mode: 'UPI' as const, tag: null, note: null };

  beforeEach(() =>
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] }),
  );

  it('sends the Idempotency-Key and gives up with TimeoutError after 30 s', fakeAsync(() => {
    const http = TestBed.inject(HttpTestingController);
    let err: Error | undefined;
    TestBed.inject(ExpenseApiService).create(input, 'key-aaaaaaaa').subscribe({ error: (e) => (err = e) });
    const req = http.expectOne('/expenses');
    expect(req.request.headers.get('Idempotency-Key')).toBe('key-aaaaaaaa');
    tick(CREATE_TIMEOUT_MS - 1);
    expect(err).toBeUndefined();
    tick(1);
    expect(err?.name).toBe('TimeoutError');
  }));
});
