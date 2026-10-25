import { HttpResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { Observable, of } from 'rxjs';
import { ExpenseApiService } from './expense-api.service';
import { ExpenseQueueService, QUEUE_MIGRATED_KEY, QUEUE_OWNER, QUEUE_STORAGE, QUEUE_STORAGE_KEY, queueKeyFor } from './expense-queue.service';
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
const item = (key: string) => ({ key, input: input(100), status: 'pending' });

describe('ExpenseQueueService per-user namespace (T33)', () => {
  let store: ReturnType<typeof memoryStorage>;
  let owner: string | null;
  let sent: { key: string; owner: string | null }[];
  let queue: ExpenseQueueService;

  const keysIn = (k: string) => (store.data[k] ? JSON.parse(store.data[k]).map((i: { key: string }) => i.key) : undefined);

  beforeEach(() => {
    store = memoryStorage();
    owner = null;
    sent = [];
    const api = {
      categories: () => [],
      create: (_i: ExpenseInput, key: string): Observable<HttpResponse<ExpenseView>> => {
        sent.push({ key, owner });
        return of(new HttpResponse<ExpenseView>({ status: 201 }));
      },
    };
    TestBed.configureTestingModule({
      providers: [
        { provide: ExpenseApiService, useValue: api },
        { provide: QUEUE_STORAGE, useValue: store },
        { provide: QUEUE_OWNER, useValue: () => owner },
      ],
    });
    queue = TestBed.inject(ExpenseQueueService);
  });

  it('a signed-in user queues under their own key, not the shared legacy key', () => {
    owner = 'user-a';
    queue.enqueue(input(500), 'k-a-0001');
    expect(keysIn(queueKeyFor('user-a'))).toEqual(['k-a-0001']);
    expect(store.data[QUEUE_STORAGE_KEY]).toBeUndefined();
  });

  it('legacy un-namespaced items are adopted by the first signed-in user, then the legacy key is removed', async () => {
    store.data[QUEUE_STORAGE_KEY] = JSON.stringify([item('legacy-01'), item('legacy-02')]);
    store.data[queueKeyFor('user-a')] = JSON.stringify([item('legacy-01'), item('mine-0001')]);
    owner = 'user-a';
    queue.reload();
    expect(keysIn(queueKeyFor('user-a'))).toEqual(['legacy-01', 'mine-0001', 'legacy-02']); // no duplicate key
    expect(store.data[QUEUE_STORAGE_KEY]).toBeUndefined();

    owner = 'user-b'; // a later user gets nothing from the legacy key
    queue.reload();
    expect(queue.items()).toEqual([]);
    expect(keysIn(queueKeyFor('user-b'))).toBeUndefined();
  });

  it("sign-out keeps the user's queue; another user never sends it; it sends on that user's next sign-in", async () => {
    owner = 'user-a';
    queue.enqueue(input(500), 'k-a-0001');

    owner = null; // signed out
    queue.reload();
    expect(queue.items()).toEqual([]);
    expect(keysIn(queueKeyFor('user-a'))).toEqual(['k-a-0001']);

    owner = 'user-b';
    await queue.flush();
    expect(sent).toEqual([]);

    owner = 'user-a';
    await queue.flush();
    expect(sent).toEqual([{ key: 'k-a-0001', owner: 'user-a' }]);
    expect(keysIn(queueKeyFor('user-a'))).toEqual([]);
  });

  it('signed out: enqueue is refused and nothing is stored anywhere', () => {
    expect(queue.hasOwner()).toBeFalse();
    expect(queue.enqueue(input(500), 'k-x-0001')).toBeFalse();
    expect(queue.items()).toEqual([]);
    expect(store.data).toEqual({});
  });

  it('legacy adoption happens once: a user signing in later (even with legacy items reappearing) adopts nothing', () => {
    store.data[QUEUE_STORAGE_KEY] = JSON.stringify([item('legacy-01')]);
    owner = 'user-a';
    queue.reload();
    expect(keysIn(queueKeyFor('user-a'))).toEqual(['legacy-01']);
    expect(store.data[QUEUE_MIGRATED_KEY]).toBe('1');

    store.data[QUEUE_STORAGE_KEY] = JSON.stringify([item('stray-001')]); // e.g. written by an old build in another tab
    owner = 'user-b';
    queue.reload();
    expect(queue.items()).toEqual([]);
    expect(keysIn(queueKeyFor('user-b'))).toBeUndefined();
  });

  it('with no legacy items, the first signed-in access still sets the marker', () => {
    owner = 'user-a';
    queue.reload();
    expect(store.data[QUEUE_MIGRATED_KEY]).toBe('1');
    store.data[QUEUE_STORAGE_KEY] = JSON.stringify([item('late-0001')]);
    owner = 'user-b';
    queue.reload();
    expect(queue.items()).toEqual([]);
  });

  it('an item attempted while signed out is never uploaded, under that user or a different one', async () => {
    expect(queue.enqueue(input(500), 'k-out-001')).toBeFalse();
    owner = 'user-b';
    await queue.flush();
    owner = 'user-a';
    await queue.flush();
    expect(sent).toEqual([]);
    expect(store.data[QUEUE_STORAGE_KEY]).toBeUndefined();
  });

  it("an unreadable queue for a new owner never keeps the previous owner's items in memory", async () => {
    owner = 'user-a';
    queue.enqueue(input(500), 'k-a-0001');
    store.data[queueKeyFor('user-b')] = '{not json';
    owner = 'user-b';
    queue.reload();
    expect(queue.items()).toEqual([]);
    await queue.flush();
    expect(sent).toEqual([]);
  });

  it('blocked storage: in-memory items stay with their owner', async () => {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        { provide: ExpenseApiService, useValue: { categories: () => [], create: (_i: ExpenseInput, key: string) => { sent.push({ key, owner }); return of(new HttpResponse<ExpenseView>({ status: 201 })); } } },
        { provide: QUEUE_STORAGE, useValue: null },
        { provide: QUEUE_OWNER, useValue: () => owner },
      ],
    });
    const q = TestBed.inject(ExpenseQueueService);
    owner = 'user-a';
    expect(q.enqueue(input(500), 'k-mem-001')).toBeFalse(); // memory only
    expect(q.items().length).toBe(1);
    owner = 'user-b';
    q.reload();
    expect(q.items()).toEqual([]);
    await q.flush();
    expect(sent).toEqual([]);
    owner = 'user-a';
    await q.flush();
    expect(sent).toEqual([{ key: 'k-mem-001', owner: 'user-a' }]);
  });
});
