import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component, Provider } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { USER_A, USER_B, tokenResponse } from '../auth/auth-testing';
import { AUTH_STORAGE, AuthSessionStore } from '../auth/session.store';
import { API_BASE } from '../expense/expense-api.service';
import { QUEUE_STORAGE } from '../expense/expense-queue.service';
import { memoryStorage } from '../expense/queue-testing';
import { FINISH_NEEDS_CONSENT, OnboardingComponent } from './onboarding.component';
import { SHELL_STEP_PROVIDERS } from './onboarding.steps';
import { wizardKeyFor } from './wizard-progress';
import { ONBOARDING_STEP } from './wizard-step';

const STATEMENT = { purpose: 'plan', version: '2026-10-v2', text: 'We keep your plan.' };

@Component({ standalone: true, template: '<p>extra</p>' })
class ExtraStep {}

describe('OnboardingComponent', () => {
  let storage: ReturnType<typeof memoryStorage>;
  let http: HttpTestingController;

  const setup = (user: string | null = USER_A, extra: Provider[] = []) => {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: API_BASE, useValue: '/api' },
        { provide: AUTH_STORAGE, useValue: storage },
        { provide: QUEUE_STORAGE, useValue: memoryStorage() },
        ...SHELL_STEP_PROVIDERS,
        ...extra,
      ],
    });
    if (user) TestBed.inject(AuthSessionStore).save(tokenResponse(user, 'r'));
    http = TestBed.inject(HttpTestingController);
    const fixture = TestBed.createComponent(OnboardingComponent);
    fixture.detectChanges();
    return fixture;
  };
  const el = (f: ComponentFixture<unknown>) => f.nativeElement as HTMLElement;
  const settle = async (f: ComponentFixture<unknown>) => {
    await f.whenStable();
    await new Promise((r) => setTimeout(r)); // let promise continuations after an HTTP flush run
    f.detectChanges();
  };
  const click = async (f: ComponentFixture<unknown>, text: string) => {
    const b = Array.from(el(f).querySelectorAll('button')).find((x) => x.textContent?.trim() === text)!;
    b.click();
    await settle(f);
  };
  const answerConsent = async (f: ComponentFixture<unknown>, given: boolean) => {
    http.expectOne('/api/me/consent').flush({ statement: STATEMENT, given });
    await settle(f);
  };
  /** Consent given, then on to the last step. */
  const toLastStep = async (f: ComponentFixture<unknown>) => {
    await answerConsent(f, true);
    await click(f, 'Next');
  };

  beforeEach(() => (storage = memoryStorage()));
  afterEach(() => TestBed.resetTestingModule());

  it('shows text and bar progress, one question per screen', async () => {
    const f = setup();
    await answerConsent(f, false);
    expect(el(f).querySelector('.progress-text')?.textContent).toBe('Step 1 of 2');
    expect(el(f).querySelector('.progress-text')?.getAttribute('aria-live')).toBe('polite');
    const bar = el(f).querySelector('[role=progressbar]')!;
    expect(bar.getAttribute('aria-valuenow')).toBe('1');
    expect(bar.getAttribute('aria-valuemax')).toBe('2');
    expect(el(f).querySelectorAll('h1').length).toBe(1);
  });

  it('blocks Next until the server says consent is given', async () => {
    const f = setup();
    await answerConsent(f, false);
    const next = () => Array.from(el(f).querySelectorAll('button')).find((b) => b.textContent?.trim() === 'Next')!;
    expect(next().disabled).toBeTrue();
  });

  it('POSTs the statement version and purpose from the GET, then re-checks the server', async () => {
    const f = setup();
    await answerConsent(f, false);
    el(f).querySelector<HTMLInputElement>('input[type=checkbox]')!.click();
    await settle(f);
    await click(f, 'Give consent');
    const post = http.expectOne((r) => r.method === 'POST' && r.url === '/api/me/consent');
    expect(post.request.body).toEqual({ purpose: 'plan', statementVersion: '2026-10-v2' });
    post.flush({}, { status: 201, statusText: 'Created' });
    await settle(f);
    await answerConsent(f, true);
    const next = Array.from(el(f).querySelectorAll('button')).find((b) => b.textContent?.trim() === 'Next')!;
    expect(next.disabled).toBeFalse();
  });

  it('shows a fixed friendly message when recording consent fails', async () => {
    const f = setup();
    await answerConsent(f, false);
    el(f).querySelector<HTMLInputElement>('input[type=checkbox]')!.click();
    await settle(f);
    await click(f, 'Give consent');
    http.expectOne((r) => r.method === 'POST').flush({ secret: 'x' }, { status: 500, statusText: 'err' });
    await settle(f);
    expect(el(f).querySelector('.error')?.textContent).toBe('We could not record your consent. Please try again.');
  });

  it('a stale-wording 400 reloads the statement, unticks the box, and one click does not post the new version', async () => {
    const f = setup();
    await answerConsent(f, false);
    el(f).querySelector<HTMLInputElement>('input[type=checkbox]')!.click();
    await settle(f);
    await click(f, 'Give consent');
    http.expectOne((r) => r.method === 'POST').flush({}, { status: 400, statusText: 'bad' });
    await settle(f);
    http.expectOne('/api/me/consent').flush({ statement: { ...STATEMENT, version: '2026-11-v3' }, given: false });
    await settle(f);
    const box = el(f).querySelector<HTMLInputElement>('input[type=checkbox]')!;
    expect(box.checked).toBeFalse();
    expect(el(f).textContent).toContain('2026-11-v3');
    expect(el(f).textContent).toContain('wording has changed');
    const give = Array.from(el(f).querySelectorAll('button')).find((b) => b.textContent?.trim() === 'Give consent')!;
    expect(give.disabled).toBeTrue();
    give.click();
    await settle(f);
    http.expectNone((r) => r.method === 'POST');
  });

  it('shows a hint while Next is disabled on the consent step', async () => {
    const f = setup();
    await answerConsent(f, false);
    expect(el(f).textContent).toContain('Give consent to continue');
  });

  it('cannot finish when the server says consent is not given, even if local state said it was', async () => {
    const f = setup();
    await toLastStep(f);
    const router = TestBed.inject(Router);
    spyOn(router, 'navigateByUrl').and.resolveTo(true);
    await click(f, 'Finish');
    http.expectOne('/api/me/consent').flush({ statement: STATEMENT, given: false }); // consent withdrawn meanwhile
    await settle(f);
    expect(router.navigateByUrl).not.toHaveBeenCalled();
    expect(el(f).querySelector('.wizard > .error')?.textContent).toBe(FINISH_NEEDS_CONSENT);
    expect(el(f).querySelector('.progress-text')?.textContent).toBe('Step 1 of 2'); // sent back to consent
    expect(storage.data[wizardKeyFor(USER_A)]).toBeDefined();
  });

  it('cannot finish when the consent check itself fails', async () => {
    const f = setup();
    await toLastStep(f);
    const router = TestBed.inject(Router);
    spyOn(router, 'navigateByUrl').and.resolveTo(true);
    await click(f, 'Finish');
    http.expectOne('/api/me/consent').flush({}, { status: 503, statusText: 'down' });
    await settle(f);
    expect(router.navigateByUrl).not.toHaveBeenCalled();
    expect(el(f).querySelector('.error')?.textContent).toContain('could not confirm');
  });

  it('finishes when the server confirms consent, and clears the saved step', async () => {
    const f = setup();
    await toLastStep(f);
    const router = TestBed.inject(Router);
    spyOn(router, 'navigateByUrl').and.resolveTo(true);
    await click(f, 'Finish');
    http.expectOne('/api/me/consent').flush({ statement: STATEMENT, given: true });
    await settle(f);
    expect(router.navigateByUrl).toHaveBeenCalledWith('/home');
    expect(storage.data[wizardKeyFor(USER_A)]).toBeUndefined();
  });

  it('resumes at the same step after the wizard is re-created, per user, storing only a position', async () => {
    const f = setup();
    await toLastStep(f);
    expect(el(f).querySelector('h1')?.textContent).toContain("You're set");
    expect(JSON.parse(storage.data[wizardKeyFor(USER_A)])).toEqual({ id: 'done', index: 1 });

    const again = setup(); // closed and reopened
    expect(el(again).querySelector('.progress-text')?.textContent).toBe('Step 2 of 2');
    expect(el(again).querySelector('h1')?.textContent).toContain("You're set");

    const other = setup(USER_B);
    expect(el(other).querySelector('.progress-text')?.textContent).toBe('Step 1 of 2');
    http.match('/api/me/consent');
  });

  it('survives blocked storage and a corrupt saved value', () => {
    storage.setItem(wizardKeyFor(USER_A), '{not json');
    expect(el(setup()).querySelector('.progress-text')?.textContent).toBe('Step 1 of 2');
    const blocked = { getItem: () => { throw new Error('x'); }, setItem: () => { throw new Error('x'); }, removeItem: () => { throw new Error('x'); } };
    storage = blocked as never;
    expect(() => setup()).not.toThrow();
  });

  it('lets a later task add a step without touching the shell, ordered by `order`', async () => {
    const f = setup(USER_A, [
      { provide: ONBOARDING_STEP, multi: true, useValue: { id: 'cycle', order: 20, question: 'Which day does your cycle start?', component: ExtraStep } },
    ]);
    await answerConsent(f, true);
    expect(el(f).querySelector('.progress-text')?.textContent).toBe('Step 1 of 3');
    await click(f, 'Next');
    expect(el(f).querySelector('h1')?.textContent).toContain('Which day');
    expect(el(f).textContent).toContain('extra');
    expect(el(f).querySelector('.progress-text')?.textContent).toBe('Step 2 of 3');
  });
});
