import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { API_BASE } from '../expense/expense-api.service';
import { allocationOption } from './allocation-chart-options';
import { HoldingApiService } from './holding-api.service';
import { allocationSlices, describeAllocation, validName } from './holding-format';
import { AggregateClassView } from './holding.models';

describe('HoldingApiService', () => {
  let http: HttpTestingController;
  let api: HoldingApiService;
  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting(), { provide: API_BASE, useValue: '/api' }] });
    http = TestBed.inject(HttpTestingController);
    api = TestBed.inject(HoldingApiService);
  });
  afterEach(() => http.verify());

  it('lists, aggregates, creates, replaces and deletes against /holdings', () => {
    const body = { assetClass: 'epf' as const, name: 'Employer EPF', investedPaise: 0, currentValuePaise: 100, monthlyContributionPaise: 0 };
    api.list().subscribe();
    expect(http.expectOne('/api/holdings').request.method).toBe('GET');
    api.aggregate().subscribe();
    expect(http.expectOne('/api/holdings/aggregate').request.method).toBe('GET');
    api.create(body).subscribe();
    const post = http.expectOne('/api/holdings');
    expect(post.request.method).toBe('POST');
    expect(post.request.body).toEqual(body);
    api.replace('h 1', body).subscribe();
    expect(http.expectOne('/api/holdings/h%201').request.method).toBe('PUT');
    api.remove('h1').subscribe();
    expect(http.expectOne('/api/holdings/h1').request.method).toBe('DELETE');
  });
});

const cls = (assetClass: AggregateClassView['assetClass'], currentValuePaise: number): AggregateClassView => ({
  assetClass, investedPaise: 0, currentValuePaise, monthlyContributionPaise: 0, count: currentValuePaise > 0 ? 1 : 0,
});

describe('holding-format', () => {
  it('accepts names of 1 to 60 characters only', () => {
    expect(validName('')).toBeFalse();
    expect(validName('   ')).toBeFalse();
    expect(validName('a')).toBeTrue();
    expect(validName('x'.repeat(60))).toBeTrue();
    expect(validName('x'.repeat(61))).toBeFalse();
  });
  it('keeps only classes with a value and takes the share against the API total', () => {
    const s = allocationSlices([cls('epf', 7500), cls('ppf', 0), cls('gold', 2500)], 10000);
    expect(s.map((x) => [x.assetClass, x.percent])).toEqual([['epf', 75], ['gold', 25]]);
    expect(allocationSlices([cls('epf', 5)], 0)[0].percent).toBe(0);
  });
  it('describes every slice by name, amount and share', () => {
    const s = allocationSlices([cls('epf', 7500), cls('gold', 2500)], 10000);
    const d = describeAllocation(s, 'T', (p) => `P${p}`);
    expect(d).toContain('EPF (provident fund) P7500, 75 percent');
    expect(d).toContain('Gold P2500, 25 percent');
    expect(describeAllocation([], '', String)).toContain('No holdings');
  });
});

describe('allocationOption', () => {
  const slices = allocationSlices([cls('epf', 7500), cls('gold', 2500)], 10000);
  const pal = { text: '#111', border: '#fff', slices: ['#1', '#2'] };
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const o = allocationOption(slices, pal, (p) => `P${p}`) as any;
  it('feeds the API values into a donut', () => {
    expect(o.series[0].type).toBe('pie');
    expect(o.series[0].radius[0]).not.toBe('0%');
    expect(o.series[0].data).toEqual([{ name: 'EPF (provident fund)', value: 7500 }, { name: 'Gold', value: 2500 }]);
  });
  it('is not colour-only: decal patterns, aria, and name/amount/share labels', () => {
    expect(o.aria.enabled).toBeTrue();
    expect(o.aria.decal.show).toBeTrue();
    expect(o.series[0].label.show).toBeTrue();
    expect(o.series[0].label.formatter({ dataIndex: 1 })).toBe('Gold\nP2500 (25%)');
    expect(o.tooltip.formatter({ dataIndex: 0 })).toContain('P7500 (75%)');
    expect(o.color).toEqual(['#1', '#2']);
  });
});
