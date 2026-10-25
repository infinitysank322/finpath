import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { AuthService } from '../auth/auth.service';
import { ExpenseApiService } from '../expense/expense-api.service';
import { QUEUE_OWNER, QUEUE_STORAGE, queueKeyFor } from '../expense/expense-queue.service';
import { memoryStorage } from '../expense/queue-testing';
import { MoreComponent } from './more.component';

const item = (key: string) => ({
  key,
  input: { txnDate: '2026-10-22', amountPaise: 100, categoryCode: 'DINING', mode: 'UPI', tag: null, note: null },
  status: 'pending',
});

describe('MoreComponent sign-out', () => {
  let store: ReturnType<typeof memoryStorage>;
  let owner: string | null;
  let signOut: jasmine.Spy;

  const render = () => {
    TestBed.configureTestingModule({
      imports: [MoreComponent],
      providers: [
        provideRouter([]),
        { provide: AuthService, useValue: { signOut } },
        { provide: ExpenseApiService, useValue: { categories: () => [] } },
        { provide: QUEUE_STORAGE, useValue: store },
        { provide: QUEUE_OWNER, useValue: () => owner },
      ],
    });
    const f = TestBed.createComponent(MoreComponent);
    f.detectChanges();
    return f;
  };
  const click = (f: ReturnType<typeof render>, label: string) => {
    const b = Array.from((f.nativeElement as HTMLElement).querySelectorAll('button')).find((x) => x.textContent!.trim() === label);
    expect(b).withContext(label).toBeDefined();
    b!.click();
    f.detectChanges();
  };

  beforeEach(() => {
    owner = 'user-a';
    store = memoryStorage();
    signOut = jasmine.createSpy('signOut').and.callFake(async () => {
      owner = null;
      return true;
    });
  });

  it('with no unsent items, signs out straight away', async () => {
    const f = render();
    click(f, 'Sign out');
    await f.whenStable();
    expect(signOut).toHaveBeenCalledTimes(1);
  });

  it('warns with the unsent count first, and keeps the queue after confirming', async () => {
    store.data[queueKeyFor('user-a')] = JSON.stringify([item('k-0000001'), item('k-0000002')]);
    const f = render();
    click(f, 'Sign out');
    expect(signOut).not.toHaveBeenCalled();
    const warning = f.nativeElement.querySelector('[data-f=unsent-warning]') as HTMLElement;
    expect(warning.getAttribute('role')).toBe('alert');
    expect(warning.textContent).toContain('2 expenses have not been sent yet');

    click(f, 'Sign out anyway');
    await f.whenStable();
    expect(signOut).toHaveBeenCalledTimes(1);
    expect(JSON.parse(store.data[queueKeyFor('user-a')]).length).toBe(2);
  });

  it('Cancel keeps the user signed in', () => {
    store.data[queueKeyFor('user-a')] = JSON.stringify([item('k-0000001')]);
    const f = render();
    click(f, 'Sign out');
    expect(f.nativeElement.querySelector('[data-f=unsent-warning]').textContent).toContain('1 expense has not been sent yet');
    click(f, 'Cancel');
    expect(signOut).not.toHaveBeenCalled();
    expect(f.nativeElement.querySelector('[data-f=unsent-warning]')).toBeNull();
  });
});
