import { HttpErrorResponse, HttpResponse } from '@angular/common/http';
import { TestBed, fakeAsync, tick } from '@angular/core/testing';
import { Observable, Subject, TimeoutError, of, throwError } from 'rxjs';
import { ExpenseApiService } from './expense-api.service';
import { BACKOFF_MS, ExpenseQueueService, QUEUE_STORAGE, QUEUE_STORAGE_KEY } from './expense-queue.service';
import { ExpenseInput, ExpenseView } from './expense.models';
import { memoryStorage } from './queue-testing';

const input = (amountPaise: number): ExpenseInput => ({
  txnDate: '2026-10-22',
  amountPaise,
  categoryCode: 'DINING',
  mode: 'UPI',
  tag: null,
  note: null,
});

type Outcome = number | 'gate' | 'timeout'; // HTTP status, or hold the request open

class FakeApi {
  calls: string[] = [];
  /** Per-key outcome; default 201. */
  outcomes = new Map<string, Outcome>();
  gate = new Subject<HttpResponse<ExpenseView>>();
  /** Keys the "server" has stored: a repeat answers 200, like the real API. */
  stored = new Set<string>();
  rows = 0;
  create(_input: ExpenseInput, key: string): Observable<HttpResponse<ExpenseView>> {
    this.calls.push(key);
    const o = this.outcomes.get(key) ?? 201;
    if (o === 'gate') return this.gate;
    if (o === 'timeout') return throwError(() => new TimeoutError());
    if (o >= 400 || o === 0) return throwError(() => new HttpErrorResponse({ status: o }));
    if (this.stored.has(key)) return of(new HttpResponse<ExpenseView>({ status: 200 }));
    this.stored.add(key);
    this.rows++;
    return of(new HttpResponse<ExpenseView>({ status: 201 }));
  }
  categories() {
    return [];
  }
}

describe('ExpenseQueueService', () => {
  let api: FakeApi;
  let store: ReturnType<typeof memoryStorage>;
  const make = (): ExpenseQueueService => {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        { provide: ExpenseApiService, useValue: api },
        { provide: QUEUE_STORAGE, useValue: store },
      ],
    });
    return TestBed.inject(ExpenseQueueService);
  };

  afterEach(() => {
    jasmine.clock().uninstall();
    TestBed.resetTestingModule();
  });

  beforeEach(() => {
    api = new FakeApi();
    store = memoryStorage();
  });

  it('enqueue keeps the item locally and persists it with its own key', () => {
    const q = make();
    q.enqueue(input(12550), 'key-aaaaaaaa');
    q.enqueue(input(900), 'key-bbbbbbbb');
    expect(q.pending().map((i) => i.key)).toEqual(['key-aaaaaaaa', 'key-bbbbbbbb']);
    const saved = JSON.parse(store.data[QUEUE_STORAGE_KEY]);
    expect(saved.length).toBe(2);
    expect(saved[0].input.amountPaise).toBe(12550);
    expect(Object.keys(saved[0].input).sort()).toEqual(['amountPaise', 'categoryCode', 'mode', 'note', 'tag', 'txnDate']);
  });

  it('survives a reload: a new service instance reads the persisted queue, ignoring corrupt entries', () => {
    const q = make();
    q.enqueue(input(500), 'key-aaaaaaaa');
    store.data[QUEUE_STORAGE_KEY] = JSON.stringify([
      ...JSON.parse(store.data[QUEUE_STORAGE_KEY]),
      { key: 'bad', status: 'pending', input: { amountPaise: 1.5 } },
    ]);
    expect(make().items().map((i) => i.key)).toEqual(['key-aaaaaaaa']);
  });

  it('reconnect syncs once: an online event flushes, and the item is gone with exactly one POST', async () => {
    const q = make();
    q.start();
    await q.flush();
    const online = spyOn(q, 'isOnline').and.returnValue(false);
    q.enqueue(input(500), 'key-aaaaaaaa'); // offline: no flush
    expect(api.calls.length).toBe(0);
    online.and.returnValue(true);
    window.dispatchEvent(new Event('online'));
    await q.flush();
    expect(api.calls).toEqual(['key-aaaaaaaa']);
    expect(q.items().length).toBe(0);
    expect(api.rows).toBe(1);
    expect(JSON.parse(store.data[QUEUE_STORAGE_KEY])).toEqual([]);
    await q.flush();
    expect(api.calls.length).toBe(1);
  });

  it('flushes on app start for items left from a previous session', async () => {
    make().enqueue(input(700), 'key-aaaaaaaa');
    const q = make();
    q.start();
    await q.flush();
    expect(api.calls).toEqual(['key-aaaaaaaa']);
    expect(q.items().length).toBe(0);
  });

  it('concurrent or double flush sends one request per item, serially and in order', async () => {
    const q = make();
    q.enqueue(input(100), 'key-aaaaaaaa');
    q.enqueue(input(200), 'key-bbbbbbbb');
    api.outcomes.set('key-aaaaaaaa', 'gate');
    const p1 = q.flush();
    const p2 = q.flush();
    window.dispatchEvent(new Event('online')); // listener not registered (no start()), must still be safe
    expect(p2).toBe(p1);
    expect(api.calls).toEqual(['key-aaaaaaaa']); // second item not started while the first is in flight
    api.gate.next(new HttpResponse<ExpenseView>({ status: 201 }));
    api.gate.complete();
    await Promise.all([p1, p2]);
    expect(api.calls).toEqual(['key-aaaaaaaa', 'key-bbbbbbbb']);
    expect(q.items().length).toBe(0);
  });

  it('start() twice and several online events still produce one POST per item', async () => {
    const q = make();
    q.enqueue(input(100), 'key-aaaaaaaa');
    q.start();
    q.start();
    window.dispatchEvent(new Event('online'));
    window.dispatchEvent(new Event('online'));
    await q.flush();
    expect(api.calls).toEqual(['key-aaaaaaaa']);
  });

  it('a 200 replay (key already stored by an earlier, lost response) clears the item without a new row', async () => {
    api.stored.add('key-aaaaaaaa');
    api.rows = 1;
    const q = make();
    q.enqueue(input(500), 'key-aaaaaaaa');
    await q.flush();
    expect(api.calls).toEqual(['key-aaaaaaaa']);
    expect(q.items().length).toBe(0);
    expect(api.rows).toBe(1);
  });

  it('keeps the item and stops on network error, 5xx and 401, then succeeds on the next flush', async () => {
    for (const status of [0, 503, 401]) {
      api = new FakeApi();
      store = memoryStorage();
      const q = make();
      q.enqueue(input(100), 'key-aaaaaaaa');
      q.enqueue(input(200), 'key-bbbbbbbb');
      api.outcomes.set('key-aaaaaaaa', status);
      await q.flush();
      expect(api.calls).withContext(`${status}`).toEqual(['key-aaaaaaaa']);
      expect(q.pending().length).toBe(2);
      api.outcomes.delete('key-aaaaaaaa');
      await q.flush();
      expect(q.items().length).toBe(0);
      expect(api.rows).toBe(2);
    }
  });

  it('400 and 409 move the item to needs attention, never retry it, and continue with the next', async () => {
    const q = make();
    q.enqueue(input(100), 'key-aaaaaaaa');
    q.enqueue(input(200), 'key-bbbbbbbb');
    q.enqueue(input(300), 'key-cccccccc');
    api.outcomes.set('key-aaaaaaaa', 400);
    api.outcomes.set('key-bbbbbbbb', 409);
    await q.flush();
    expect(q.needsAttention().map((i) => [i.key, i.reason])).toEqual([
      ['key-aaaaaaaa', 400],
      ['key-bbbbbbbb', 409],
    ]);
    expect(q.pending().length).toBe(0);
    expect(api.rows).toBe(1);
    await q.flush();
    expect(api.calls.length).toBe(3);
    q.dismiss('key-aaaaaaaa');
    expect(q.needsAttention().length).toBe(1);
  });

  describe('hardening', () => {
    it('retries with backoff 5 s, 30 s, 2 min, then holds at the cap, and resets after success', async () => {
      jasmine.clock().install();
      const q = make();
      q.enqueue(input(100), 'key-aaaaaaaa');
      api.outcomes.set('key-aaaaaaaa', 503);
      await q.flush();
      expect(api.calls.length).toBe(1);
      const step = async (ms: number, calls: number) => {
        jasmine.clock().tick(ms - 1);
        expect(api.calls.length).withContext(`before ${ms}`).toBe(calls - 1);
        jasmine.clock().tick(1);
        await q.flush();
        expect(api.calls.length).withContext(`at ${ms}`).toBe(calls);
      };
      await step(BACKOFF_MS[0], 2);
      await step(BACKOFF_MS[1], 3);
      await step(BACKOFF_MS[2], 4);
      await step(BACKOFF_MS[2], 5); // capped
      api.outcomes.delete('key-aaaaaaaa');
      await step(BACKOFF_MS[2], 6);
      expect(q.items().length).toBe(0);
      jasmine.clock().tick(10 * BACKOFF_MS[2]);
      expect(api.calls.length).toBe(6); // nothing left to retry
    });

    it('re-flushes when the tab becomes visible and when an item is enqueued while online', async () => {
      const q = make();
      q.start();
      await q.flush();
      api.outcomes.set('key-aaaaaaaa', 503);
      q.enqueue(input(100), 'key-aaaaaaaa'); // online enqueue triggers a flush
      await q.flush();
      expect(api.calls).toEqual(['key-aaaaaaaa']);
      api.outcomes.delete('key-aaaaaaaa');
      document.dispatchEvent(new Event('visibilitychange'));
      await q.flush();
      expect(api.calls).toEqual(['key-aaaaaaaa', 'key-aaaaaaaa']);
      expect(q.items().length).toBe(0);
    });

    it('an item enqueued during a running flush is sent by the same run', async () => {
      const q = make();
      q.enqueue(input(100), 'key-aaaaaaaa');
      api.outcomes.set('key-aaaaaaaa', 'gate');
      const p = q.flush();
      q.enqueue(input(200), 'key-bbbbbbbb');
      api.outcomes.delete('key-aaaaaaaa');
      api.gate.next(new HttpResponse<ExpenseView>({ status: 201 }));
      api.gate.complete();
      await p;
      expect(api.calls).toEqual(['key-aaaaaaaa', 'key-bbbbbbbb']);
      expect(q.items().length).toBe(0);
    });

    it('writes merge with what another tab stored, and storage events refresh memory', () => {
      const q = make();
      q.enqueue(input(100), 'key-aaaaaaaa');
      const stored = JSON.parse(store.data[QUEUE_STORAGE_KEY]);
      stored.push({ key: 'key-other000', status: 'pending', input: input(300) }); // written by another tab
      store.data[QUEUE_STORAGE_KEY] = JSON.stringify(stored);
      q.enqueue(input(200), 'key-bbbbbbbb');
      expect(JSON.parse(store.data[QUEUE_STORAGE_KEY]).map((i: { key: string }) => i.key)).toEqual([
        'key-aaaaaaaa',
        'key-other000',
        'key-bbbbbbbb',
      ]);
      q.start();
      api.outcomes.set('key-aaaaaaaa', 503);
      store.data[QUEUE_STORAGE_KEY] = JSON.stringify([]); // other tab sent everything
      window.dispatchEvent(new StorageEvent('storage', { key: QUEUE_STORAGE_KEY }));
      expect(q.items().length).toBe(0);
    });

    it('enqueue reports whether the item reached storage', () => {
      expect(make().enqueue(input(100), 'key-aaaaaaaa')).toBeTrue();
      store = memoryStorage();
      store.setItem = () => {
        throw new Error('quota');
      };
      const q = make();
      expect(q.enqueue(input(100), 'key-bbbbbbbb')).toBeFalse();
      expect(q.pending().length).toBe(1); // still held in memory
      TestBed.resetTestingModule();
      TestBed.configureTestingModule({
        providers: [
          { provide: ExpenseApiService, useValue: api },
          { provide: QUEUE_STORAGE, useValue: null },
        ],
      });
      expect(TestBed.inject(ExpenseQueueService).enqueue(input(100), 'key-cccccccc')).toBeFalse();
    });

    it('a timed-out POST is retryable, not terminal', async () => {
      jasmine.clock().install();
      const q = make();
      q.enqueue(input(100), 'key-aaaaaaaa');
      api.outcomes.set('key-aaaaaaaa', 'timeout');
      await q.flush();
      expect(q.pending().length).toBe(1);
      expect(q.needsAttention().length).toBe(0);
    });

    it('other 4xx are terminal, but 401, 408 and 429 retry', async () => {
      for (const status of [422, 404, 413]) {
        api = new FakeApi();
        store = memoryStorage();
        const q = make();
        q.enqueue(input(100), 'key-aaaaaaaa');
        api.outcomes.set('key-aaaaaaaa', status);
        await q.flush();
        expect(q.needsAttention().map((i) => i.reason)).withContext(`${status}`).toEqual([status]);
      }
      jasmine.clock().install();
      for (const status of [401, 408, 429]) {
        api = new FakeApi();
        store = memoryStorage();
        const q = make();
        q.enqueue(input(100), 'key-aaaaaaaa');
        api.outcomes.set('key-aaaaaaaa', status);
        await q.flush();
        expect(q.pending().length).withContext(`${status}`).toBe(1);
      }
    });

    it('403 pauses: item kept, flag set, no backoff timer, resumes on the next trigger', async () => {
      jasmine.clock().install();
      const q = make();
      q.enqueue(input(100), 'key-aaaaaaaa');
      api.outcomes.set('key-aaaaaaaa', 403);
      await q.flush();
      expect(q.consentNeeded()).toBeTrue();
      jasmine.clock().tick(10 * BACKOFF_MS[2]);
      expect(api.calls.length).toBe(1);
      api.outcomes.delete('key-aaaaaaaa');
      await q.flush();
      expect(q.consentNeeded()).toBeFalse();
      expect(q.items().length).toBe(0);
    });
  });
});
