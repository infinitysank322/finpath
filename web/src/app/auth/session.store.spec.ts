import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { memoryStorage } from '../expense/queue-testing';
import { QUEUE_STORAGE_KEY, queueKeyFor } from '../expense/expense-queue.service';
import { AuthService, SIGNIN_STORAGE } from './auth.service';
import { USER_A, tokenResponse } from './auth-testing';
import { AUTH_STORAGE, AuthSessionStore, EXPIRY_SKEW_MS, SESSION_KEY, isExpired, subjectOf } from './session.store';

describe('AuthSessionStore', () => {
  let storage: ReturnType<typeof memoryStorage>;
  const make = () => {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({ providers: [{ provide: AUTH_STORAGE, useValue: storage }] });
    return TestBed.inject(AuthSessionStore);
  };

  beforeEach(() => (storage = memoryStorage()));
  afterEach(() => TestBed.resetTestingModule());

  it('starts signed out with nothing stored', () => {
    expect(make().isSignedIn()).toBeFalse();
  });

  it('survives an app restart: a new instance restores the same session from storage', () => {
    const before = make();
    const saved = before.save(tokenResponse(USER_A, 'refresh-1'), 1_000_000);
    expect(saved.expiresAt).toBe(1_000_000 + 900_000);
    expect(JSON.parse(storage.data[SESSION_KEY])).toEqual(saved);

    const after = make(); // fresh injector = fresh instance, as after a reload or webview restart
    expect(after).not.toBe(before);
    expect(after.isSignedIn()).toBeTrue();
    expect(after.session()).toEqual(saved);
    expect(after.userId()).toBe(USER_A);
  });

  it('clear() removes the stored session', () => {
    const s = make();
    s.save(tokenResponse(USER_A, 'r'));
    s.clear();
    expect(s.isSignedIn()).toBeFalse();
    expect(storage.data[SESSION_KEY]).toBeUndefined();
    expect(make().isSignedIn()).toBeFalse();
  });

  it('ignores a corrupt or wrong-shaped stored value', () => {
    storage.data[SESSION_KEY] = '{not json';
    expect(make().isSignedIn()).toBeFalse();
    storage.data[SESSION_KEY] = JSON.stringify({ accessToken: 'a' });
    expect(make().isSignedIn()).toBeFalse();
  });

  it('never throws when storage itself throws', () => {
    const boom = () => {
      throw new Error('blocked');
    };
    storage = { data: {}, getItem: boom, setItem: boom, removeItem: boom } as unknown as ReturnType<typeof memoryStorage>;
    const s = make();
    expect(s.isSignedIn()).toBeFalse();
    expect(() => s.save(tokenResponse(USER_A, 'r'))).not.toThrow();
    expect(s.isSignedIn()).toBeTrue(); // signed in for this run, in memory
    expect(() => s.clear()).not.toThrow();
  });

  it('follows another tab via the storage event', () => {
    const s = make();
    storage.data[SESSION_KEY] = JSON.stringify({ accessToken: 'x', expiresAt: 1, refreshToken: 'r', userId: null });
    window.dispatchEvent(new StorageEvent('storage', { key: SESSION_KEY }));
    expect(s.isSignedIn()).toBeTrue();
  });

  it('reads sub without verification, and only a safe key fragment', () => {
    expect(subjectOf(tokenResponse(USER_A, 'r').accessToken)).toBe(USER_A);
    expect(subjectOf('nope')).toBeNull();
    expect(subjectOf(tokenResponse('../x', 'r').accessToken)).toBeNull();
  });

  it('treats a token as expired 30 s early', () => {
    const s = { accessToken: 'a', refreshToken: 'r', userId: null, expiresAt: 100_000 };
    expect(isExpired(s, 100_000 - EXPIRY_SKEW_MS - 1)).toBeFalse();
    expect(isExpired(s, 100_000 - EXPIRY_SKEW_MS)).toBeTrue();
  });
});

describe('AuthService.signOut', () => {
  it('clears the session, routes to /sign-in, and leaves queued expenses on the device', async () => {
    const storage = memoryStorage({
      [queueKeyFor(USER_A)]: '[{"key":"k1"}]',
      [QUEUE_STORAGE_KEY]: '[]',
    });
    TestBed.configureTestingModule({
      providers: [
        provideRouter([{ path: 'sign-in', children: [] }]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: AUTH_STORAGE, useValue: storage },
        { provide: SIGNIN_STORAGE, useValue: memoryStorage() },
      ],
    });
    TestBed.inject(AuthSessionStore).save(tokenResponse(USER_A, 'r'));
    await TestBed.inject(AuthService).signOut();
    expect(TestBed.inject(AuthSessionStore).isSignedIn()).toBeFalse();
    expect(storage.data[SESSION_KEY]).toBeUndefined();
    expect(storage.data[queueKeyFor(USER_A)]).toBe('[{"key":"k1"}]');
    expect(TestBed.inject(Router).url).toBe('/sign-in');
  });
});
