import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { API_BASE } from '../api-base';
import { DRAFT_KEY, DRAFT_TTL_MS, saveDraft, takeDraft } from '../expense/draft';
import { AddExpenseComponent } from '../expense/add-expense.component';
import { ExpenseQueueService, QUEUE_STORAGE, QUEUE_STORAGE_KEY, queueKeyFor } from '../expense/expense-queue.service';
import { ExpenseInput } from '../expense/expense.models';
import { memoryStorage } from '../expense/queue-testing';
import { USER_A, USER_B, tokenResponse } from './auth-testing';
import { authInterceptor } from './auth.interceptor';
import { AuthService, REFRESH_LOCK } from './auth.service';
import { LocalDataService } from './local-data.service';
import { AUTH_STORAGE, AuthSessionStore, SESSION_KEY } from './session.store';
import { SIGNIN_KEY, SIGNIN_STORAGE } from './signin-storage';

const BASE = 'http://localhost:8080';
const item = (key: string) => ({
  key,
  input: { txnDate: '2026-10-22', amountPaise: 100, categoryCode: 'DINING', mode: 'UPI', tag: null, note: null },
  status: 'pending',
});

describe('T33b session cleanup', () => {
  let local: ReturnType<typeof memoryStorage>; // localStorage stand-in (session + queue share it, as in the browser)
  let tab: ReturnType<typeof memoryStorage>; // sessionStorage stand-in
  let ctrl: HttpTestingController;
  let auth: AuthService;
  let store: AuthSessionStore;
  let queue: ExpenseQueueService;
  let navigate: jasmine.Spy;

  beforeEach(() => {
    local = memoryStorage();
    tab = memoryStorage();
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        provideHttpClient(withInterceptors([authInterceptor])),
        provideHttpClientTesting(),
        { provide: API_BASE, useValue: BASE },
        { provide: AUTH_STORAGE, useValue: local },
        { provide: QUEUE_STORAGE, useValue: local },
        { provide: SIGNIN_STORAGE, useValue: tab },
        { provide: REFRESH_LOCK, useValue: <T>(fn: () => Promise<T>) => fn() },
      ],
    });
    ctrl = TestBed.inject(HttpTestingController);
    auth = TestBed.inject(AuthService);
    store = TestBed.inject(AuthSessionStore);
    queue = TestBed.inject(ExpenseQueueService);
    navigate = spyOn(TestBed.inject(Router), 'navigateByUrl').and.resolveTo(true);
  });
  afterEach(() => ctrl.verify());

  const signInAs = (user: string, refresh = 'r1') => store.save(tokenResponse(user, refresh));
  const queued = (user: string) => JSON.parse(local.data[queueKeyFor(user)] ?? '[]') as unknown[];

  describe('(1) logout', () => {
    it('sign-out POSTs /auth/logout with the refresh token, without a bearer, and does not wait for it', async () => {
      signInAs(USER_A, 'r-secret');
      await auth.signOut();
      const req = ctrl.expectOne(`${BASE}/auth/logout`);
      expect(req.request.body).toEqual({ refreshToken: 'r-secret' });
      expect(req.request.headers.has('Authorization')).toBeFalse();
      expect(store.isSignedIn()).toBeFalse(); // signed out before the response
      expect(navigate).toHaveBeenCalledWith('/sign-in');
      req.flush(null, { status: 204, statusText: 'No Content' });
    });

    it('a failing logout call (network error / 500) does not break sign-out', async () => {
      signInAs(USER_A);
      await auth.signOut();
      ctrl.expectOne(`${BASE}/auth/logout`).flush('x', { status: 500, statusText: 'Server Error' });
      expect(store.isSignedIn()).toBeFalse();
      expect(local.data[SESSION_KEY]).toBeUndefined();
    });

    it('no logout call when there is no session', async () => {
      await auth.signOut();
      ctrl.expectNone(`${BASE}/auth/logout`);
      expect(navigate).toHaveBeenCalledWith('/sign-in');
    });
  });

  describe('(2) DELETE /me and consent withdrawal', () => {
    const seed = () => {
      signInAs(USER_A);
      local.data[queueKeyFor(USER_A)] = JSON.stringify([item('k-0000001')]);
      tab.data[SIGNIN_KEY] = JSON.stringify({ verifier: 'v', state: 's', returnTo: '/home' });
      tab.data[DRAFT_KEY] = JSON.stringify({ amountText: '5', category: null, mode: null, tag: null, note: '', userId: USER_A, savedAt: Date.now() });
    };
    const noKeyForUser = (user: string) => {
      const all = [...Object.keys(local.data), ...Object.keys(tab.data)].filter((k) => k.startsWith('finpath.'));
      expect(all.filter((k) => k.includes(user))).toEqual([]);
      expect(all).not.toContain(SESSION_KEY);
      expect(all).not.toContain(SIGNIN_KEY);
      expect(all).not.toContain(DRAFT_KEY);
    };

    it('after DELETE /me: no finpath.* key for that user remains, and the queue view is empty', async () => {
      seed();
      local.data[queueKeyFor(USER_B)] = JSON.stringify([item('k-0000002')]); // someone else's, not this user's
      await auth.accountDeleted();
      noKeyForUser(USER_A);
      expect(queue.items().length).toBe(0);
      expect(store.isSignedIn()).toBeFalse();
      expect(navigate).toHaveBeenCalledWith('/sign-in');
      ctrl.expectNone(`${BASE}/auth/logout`); // the account is gone; nothing to revoke
    });

    it('on consent withdrawal: the same wipe', async () => {
      seed();
      await auth.consentWithdrawn();
      noKeyForUser(USER_A);
    });

    it('purge rule: with a session, queues of other user ids are removed; own queue, legacy key and marker stay', () => {
      signInAs(USER_A);
      local.data[queueKeyFor(USER_A)] = JSON.stringify([item('k-0000001')]);
      local.data[queueKeyFor(USER_B)] = JSON.stringify([item('k-0000002')]);
      local.data[queueKeyFor('someone-else')] = '[]';
      local.data['finpath.expenseQueue.migrated'] = '1';
      expect(TestBed.inject(LocalDataService).purgeOrphans()).toBe(2);
      expect(Object.keys(local.data).filter((k) => k.startsWith(QUEUE_STORAGE_KEY + '.v1'))).toEqual([]);
      expect(queued(USER_A).length).toBe(1);
      expect(local.data['finpath.expenseQueue.migrated']).toBe('1');
    });

    it('purge rule never runs with no session (a signed-out user keeps unsent items for next sign-in)', () => {
      local.data[queueKeyFor(USER_B)] = JSON.stringify([item('k-0000002')]);
      expect(TestBed.inject(LocalDataService).purgeOrphans()).toBe(0);
      expect(queued(USER_B).length).toBe(1);
    });
  });

  describe('(3) Sign out and discard', () => {
    it('leaves zero queued items for that user, clears the session, and still calls logout', async () => {
      signInAs(USER_A, 'r9');
      queue.enqueue(item('k').input as ExpenseInput, 'k-0000001');
      queue.enqueue(item('k').input as ExpenseInput, 'k-0000002');
      expect(queue.items().length).toBe(2);
      await auth.signOut({ discardQueue: true });
      expect(queue.items().length).toBe(0);
      expect(local.data[queueKeyFor(USER_A)]).toBeUndefined();
      expect(store.isSignedIn()).toBeFalse();
      ctrl.expectOne(`${BASE}/auth/logout`).flush(null, { status: 204, statusText: 'No Content' });
    });

    it('plain sign-out still keeps the queue', async () => {
      signInAs(USER_A);
      queue.enqueue(item('k').input as ExpenseInput, 'k-0000001');
      await auth.signOut();
      expect(queued(USER_A).length).toBe(1);
      ctrl.expectOne(`${BASE}/auth/logout`).flush(null, { status: 204, statusText: 'No Content' });
    });
  });

  describe('(4) draft ownership and expiry', () => {
    const fields = { amountText: '12', category: 'GROCERIES', mode: 'UPI' as const, tag: null, note: 'x' };

    it("a draft from user A is not shown to user B (and is deleted unread)", () => {
      saveDraft(tab, fields, USER_A);
      expect(takeDraft(tab, USER_B)).toBeNull();
      expect(tab.data[DRAFT_KEY]).toBeUndefined();
    });

    it('the same user gets it once; a signed-out-typed draft goes to whoever signs in within the TTL', () => {
      saveDraft(tab, fields, USER_A);
      expect(takeDraft(tab, USER_A)?.amountText).toBe('12');
      expect(takeDraft(tab, USER_A)).toBeNull();
      saveDraft(tab, fields, null);
      expect(takeDraft(tab, USER_B)?.amountText).toBe('12');
    });

    it('expires after the TTL', () => {
      const t0 = 1_000_000;
      saveDraft(tab, fields, USER_A, t0);
      expect(takeDraft(tab, USER_A, t0 + DRAFT_TTL_MS + 1)).toBeNull();
      saveDraft(tab, fields, USER_A, t0);
      expect(takeDraft(tab, USER_A, t0 + DRAFT_TTL_MS - 1)).not.toBeNull();
    });

    it('with nobody signed in nothing is returned', () => {
      saveDraft(tab, fields, USER_A);
      expect(takeDraft(tab, null)).toBeNull();
    });

    it('the add-expense screen shows A\'s draft to A but not to B', () => {
      signInAs(USER_B);
      saveDraft(tab, fields, USER_A);
      const b = TestBed.runInInjectionContext(() => new AddExpenseComponent());
      expect(b.amountText()).toBe('');
      expect(b.category()).toBeNull();
      signInAs(USER_A);
      saveDraft(tab, fields, USER_A);
      const a = TestBed.runInInjectionContext(() => new AddExpenseComponent());
      expect(a.amountText()).toBe('12');
    });
  });

  describe('(5) forced sign-out on a rejected refresh while saving', () => {
    const settle = () => new Promise<void>((r) => setTimeout(r, 0));

    it('keeps the typed entry as a draft and restores it after sign-in as the same user', async () => {
      signInAs(USER_A, 'r-old');
      const c = TestBed.runInInjectionContext(() => new AddExpenseComponent());
      for (const k of ['4', '2']) c.press(k);
      c.category.set('GROCERIES');
      c.mode.set('UPI');
      c.note.set('milk');
      c.save();

      ctrl.expectOne(`${BASE}/expenses`).flush({}, { status: 401, statusText: 'Unauthorized' });
      await settle();
      ctrl.expectOne(`${BASE}/auth/refresh`).flush({}, { status: 401, statusText: 'Unauthorized' });
      await settle();

      expect(store.isSignedIn()).toBeFalse(); // forced out
      expect(navigate).toHaveBeenCalledWith('/sign-in');
      expect(JSON.parse(tab.data[DRAFT_KEY])).toEqual(jasmine.objectContaining({ amountText: '42', userId: USER_A, note: 'milk' }));

      signInAs(USER_A, 'r-new'); // signs back in
      const restored = TestBed.runInInjectionContext(() => new AddExpenseComponent());
      expect(restored.amountPaise()).toBe(4200);
      expect(restored.category()).toBe('GROCERIES');
      expect(restored.mode()).toBe('UPI');
      expect(restored.note()).toBe('milk');
    });

    it('a draft written for the forced-out user is not shown to a different user who signs in', async () => {
      signInAs(USER_A, 'r-old');
      const c = TestBed.runInInjectionContext(() => new AddExpenseComponent());
      c.press('7');
      c.category.set('GROCERIES');
      c.mode.set('UPI');
      c.save();
      ctrl.expectOne(`${BASE}/expenses`).flush({}, { status: 401, statusText: 'Unauthorized' });
      await settle();
      ctrl.expectOne(`${BASE}/auth/refresh`).flush({}, { status: 401, statusText: 'Unauthorized' });
      await settle();
      signInAs(USER_B, 'rb');
      const other = TestBed.runInInjectionContext(() => new AddExpenseComponent());
      expect(other.amountText()).toBe('');
    });
  });
});
