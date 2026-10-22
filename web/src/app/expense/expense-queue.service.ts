import { HttpErrorResponse } from '@angular/common/http';
import { Injectable, InjectionToken, OnDestroy, computed, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { ExpenseApiService } from './expense-api.service';
import { ExpenseInput } from './expense.models';

export const QUEUE_STORAGE_KEY = 'finpath.expenseQueue.v1';
/** Retry delays after a failed flush; the last value is the cap. */
export const BACKOFF_MS = [5_000, 30_000, 120_000];

/** Storage seam so tests (and a later IndexedDB move) do not touch the service. */
export const QUEUE_STORAGE = new InjectionToken<Pick<Storage, 'getItem' | 'setItem'> | null>('QUEUE_STORAGE', {
  providedIn: 'root',
  factory: () => {
    try {
      return globalThis.localStorage ?? null;
    } catch {
      return null; // storage blocked: the queue still works for this session, in memory
    }
  },
});

export type QueuedStatus = 'pending' | 'attention';

/** Payload is exactly ExpenseInput (no account/card numbers) plus the item's own Idempotency-Key. */
export interface QueuedExpense {
  key: string;
  input: ExpenseInput;
  status: QueuedStatus;
  /** HTTP status that moved the item to 'attention'. */
  reason?: number;
}

/** Any 4xx except 401 (sign in), 403 (consent), 408 (timeout) and 429 (rate limit) will never succeed on retry. */
function isTerminal(e: unknown): e is HttpErrorResponse {
  return (
    e instanceof HttpErrorResponse &&
    e.status >= 400 &&
    e.status < 500 &&
    ![401, 403, 408, 429].includes(e.status)
  );
}

function isItem(x: unknown): x is QueuedExpense {
  const o = x as QueuedExpense | null;
  return (
    !!o &&
    typeof o.key === 'string' &&
    (o.status === 'pending' || o.status === 'attention') &&
    !!o.input &&
    Number.isInteger(o.input.amountPaise) &&
    typeof o.input.categoryCode === 'string' &&
    typeof o.input.mode === 'string' &&
    typeof o.input.txnDate === 'string'
  );
}

/**
 * Offline expense queue (FR-04, T43). Each item keeps its own Idempotency-Key. Flushing is serial and
 * single-flight; replays are safe because the API answers 200 for a key it has already stored, and whether a
 * queued row duplicates an imported/SMS one is the API's dedupe decision, not ours.
 */
@Injectable({ providedIn: 'root' })
export class ExpenseQueueService implements OnDestroy {
  private readonly api = inject(ExpenseApiService);
  private readonly storage = inject(QUEUE_STORAGE);

  private readonly _items = signal<QueuedExpense[]>(this.load() ?? []);
  readonly items = this._items.asReadonly();
  readonly pending = computed(() => this._items().filter((i) => i.status === 'pending'));
  readonly needsAttention = computed(() => this._items().filter((i) => i.status === 'attention'));
  /** True after a 403: sending is paused until the next explicit trigger (consent has to be granted first). */
  readonly consentNeeded = signal(false);

  private inFlight: Promise<void> | null = null;
  private started = false;
  private attempt = 0;
  private timer: ReturnType<typeof setTimeout> | null = null;
  private readonly onOnline = () => void this.flush();
  private readonly onVisible = () => {
    if (document.visibilityState === 'visible') void this.flush();
  };
  private readonly onStorage = (e: StorageEvent) => {
    if (e.key === null || e.key === QUEUE_STORAGE_KEY) this.refresh();
  };

  /** Flush on app start, on 'online', on returning to the tab, and when another tab changes the queue. Idempotent. */
  start(): void {
    if (this.started) return;
    this.started = true;
    globalThis.addEventListener?.('online', this.onOnline);
    globalThis.addEventListener?.('storage', this.onStorage);
    globalThis.document?.addEventListener?.('visibilitychange', this.onVisible);
    void this.flush();
  }

  ngOnDestroy(): void {
    this.clearTimer();
    globalThis.removeEventListener?.('online', this.onOnline);
    globalThis.removeEventListener?.('storage', this.onStorage);
    globalThis.document?.removeEventListener?.('visibilitychange', this.onVisible);
  }

  isOnline(): boolean {
    return globalThis.navigator?.onLine !== false;
  }

  /** Returns false when the item could not be written to device storage (it is then held in memory only). */
  enqueue(input: ExpenseInput, key: string): boolean {
    const persisted = this.mutate((items) =>
      items.some((i) => i.key === key) ? items : [...items, { key, input, status: 'pending' }],
    );
    if (this.started && this.isOnline()) void this.flush();
    return persisted;
  }

  /** Remove an item (used to dismiss a needs-attention entry the user has seen). */
  dismiss(key: string): void {
    this.mutate((items) => items.filter((i) => i.key !== key));
  }

  /** Single-flight: a second call while one is running shares the running promise, so no item is POSTed twice. */
  flush(): Promise<void> {
    if (this.inFlight) return this.inFlight;
    this.clearTimer();
    this.inFlight = this.run().finally(() => (this.inFlight = null));
    return this.inFlight;
  }

  private async run(): Promise<void> {
    this.consentNeeded.set(false);
    for (;;) {
      this.refresh(); // another tab may have sent or removed items; also picks up items added during the run
      const item = this.pending()[0];
      if (!item) {
        this.attempt = 0;
        return;
      }
      try {
        await firstValueFrom(this.api.create(item.input, item.key)); // 201 created or 200 replay: both done
        this.mutate((items) => items.filter((i) => i.key !== item.key));
      } catch (e) {
        if (isTerminal(e)) {
          const reason = e.status;
          this.mutate((items) => items.map((i) => (i.key === item.key ? { ...i, status: 'attention', reason } : i)));
        } else if (e instanceof HttpErrorResponse && e.status === 403) {
          this.consentNeeded.set(true); // pause; no timer, the user has to act first
          return;
        } else {
          this.scheduleRetry(); // network, timeout, 5xx, 401, 408, 429
          return;
        }
      }
    }
  }

  private scheduleRetry(): void {
    this.clearTimer();
    const delay = BACKOFF_MS[Math.min(this.attempt, BACKOFF_MS.length - 1)];
    this.attempt++;
    this.timer = setTimeout(() => {
      this.timer = null;
      void this.flush();
    }, delay);
  }

  private clearTimer(): void {
    if (this.timer !== null) clearTimeout(this.timer);
    this.timer = null;
  }

  /** Pull the persisted queue into memory (other tabs write to the same storage). */
  private refresh(): void {
    const stored = this.load();
    if (stored) this._items.set(stored);
  }

  /**
   * Read-modify-write against what is in storage right now, so a write never clobbers another tab's changes.
   * Returns whether the result was persisted.
   */
  private mutate(fn: (items: QueuedExpense[]) => QueuedExpense[]): boolean {
    const next = fn(this.load() ?? this._items());
    this._items.set(next);
    try {
      if (!this.storage) return false;
      this.storage.setItem(QUEUE_STORAGE_KEY, JSON.stringify(next));
      return true;
    } catch {
      return false; // quota or blocked: the in-memory copy still holds for this session
    }
  }

  /** null = storage unavailable or unreadable (distinct from an empty queue). */
  private load(): QueuedExpense[] | null {
    try {
      if (!this.storage) return null;
      const raw = this.storage.getItem(QUEUE_STORAGE_KEY);
      const parsed: unknown = raw ? JSON.parse(raw) : [];
      return Array.isArray(parsed) ? parsed.filter(isItem) : [];
    } catch {
      return null;
    }
  }
}
