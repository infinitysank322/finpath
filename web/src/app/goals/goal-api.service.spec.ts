import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { API_BASE } from '../expense/expense-api.service';
import { GoalApiService } from './goal-api.service';
import { progressPercent, rupeesTextToPaiseOrZero } from './goal-format';

describe('GoalApiService', () => {
  let http: HttpTestingController;
  let api: GoalApiService;
  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting(), { provide: API_BASE, useValue: '/api' }] });
    http = TestBed.inject(HttpTestingController);
    api = TestBed.inject(GoalApiService);
  });
  afterEach(() => http.verify());

  it('lists, creates, replaces and deletes against /goals', () => {
    const body = { type: 'home' as const, targetAmountPaise: 100, targetDate: '2030-01-01', currentAmountPaise: 0, priority: 5 };
    api.list().subscribe();
    expect(http.expectOne('/api/goals').request.method).toBe('GET');
    api.create(body).subscribe();
    const post = http.expectOne('/api/goals');
    expect(post.request.method).toBe('POST');
    expect(post.request.body).toEqual(body);
    api.replace('g 1', body).subscribe();
    expect(http.expectOne('/api/goals/g%201').request.method).toBe('PUT');
    api.remove('g1').subscribe();
    expect(http.expectOne('/api/goals/g1').request.method).toBe('DELETE');
  });

  it('passes plannedMonthlyPaise as a query parameter to the status endpoint', () => {
    api.status('g1', 250000).subscribe();
    const r = http.expectOne((q) => q.url === '/api/goals/g1/status');
    expect(r.request.params.get('plannedMonthlyPaise')).toBe('250000');
  });
});

describe('goal-format', () => {
  it('parses rupees to paise, allowing zero, rejecting junk', () => {
    expect(rupeesTextToPaiseOrZero('0')).toBe(0);
    expect(rupeesTextToPaiseOrZero('1,50,000.5')).toBe(15000050);
    expect(rupeesTextToPaiseOrZero('abc')).toBeNull();
    expect(rupeesTextToPaiseOrZero('-5')).toBeNull();
  });
  it('clamps the display percent to 0..100 using whole numbers', () => {
    expect(progressPercent(0, 100)).toBe(0);
    expect(progressPercent(1, 3)).toBe(33);
    expect(progressPercent(500, 100)).toBe(100);
    expect(progressPercent(5, 0)).toBe(0);
  });
});
