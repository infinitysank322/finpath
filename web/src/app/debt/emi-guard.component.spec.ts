import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Observable, of, throwError } from 'rxjs';
import { EmiGuardComponent } from './emi-guard.component';
import { GuardApiService } from './guard-api.service';
import { GuardDialogComponent } from './guard-dialog.component';
import { GuardView } from './guard.models';

const warn: GuardView = { ratio: '0.4987000000', status: 'WARN', headroomPaise: 1300, totalEmiPaise: 4987000, monthlyIncomePaise: 10000000 };
const block: GuardView = { ...warn, ratio: '0.5140000000', status: 'BLOCK', headroomPaise: 0 };

class FakeApi {
  result: Observable<GuardView> = of(warn);
  current() { return this.result; }
}

describe('EmiGuardComponent', () => {
  let api: FakeApi;
  const setup = () => {
    TestBed.configureTestingModule({ imports: [EmiGuardComponent], providers: [{ provide: GuardApiService, useValue: api }] });
    const f = TestBed.createComponent(EmiGuardComponent);
    f.detectChanges();
    return f.nativeElement as HTMLElement;
  };
  beforeEach(() => (api = new FakeApi()));

  it('shows the ratio and the 40% marker', () => {
    const root = setup();
    expect(root.querySelector('[data-testid=ratio]')!.textContent).toContain('49.87%');
    expect(root.querySelector('[data-testid=warn-marker]')).not.toBeNull();
    expect((root.querySelector('[data-testid=warn-marker]') as HTMLElement).style.left).toBe('40%');
    expect(root.textContent).toContain('40%');
  });

  it('states WARN in words and a symbol, not colour alone', () => {
    const root = setup();
    const s = root.querySelector('[data-testid=status]')!.textContent!;
    expect(s).toContain('Warning');
    expect(s).toContain('⚠');
    expect(root.querySelector('[role=meter]')!.getAttribute('aria-valuetext')).toContain('Warning');
  });

  it('shows money from the API in INR', () => {
    const root = setup();
    expect(root.querySelector('[data-testid=emi]')!.textContent).toContain('49,870');
  });

  it('shows an error with retry', () => {
    api.result = throwError(() => ({ status: 500 }));
    const root = setup();
    expect(root.querySelector('[role=alert]')).not.toBeNull();
  });
});

@Component({
  standalone: true,
  imports: [GuardDialogComponent],
  template: `<app-guard-dialog [guard]="g()" (confirmed)="yes = yes + 1" (cancelled)="no = no + 1" />`,
})
class Host {
  g = signal<GuardView | null>(null);
  yes = 0;
  no = 0;
}

describe('GuardDialogComponent', () => {
  const mount = () => {
    const f = TestBed.configureTestingModule({ imports: [Host] }).createComponent(Host);
    f.detectChanges();
    return f;
  };

  it('stays closed until a WARN or BLOCK guard arrives, and not for OK', () => {
    const f = mount();
    const dlg = f.nativeElement.querySelector('dialog') as HTMLDialogElement;
    expect(dlg.open).toBeFalse();
    f.componentInstance.g.set({ ...warn, status: 'OK' });
    f.detectChanges();
    expect(dlg.open).toBeFalse();
    f.componentInstance.g.set(block);
    f.detectChanges();
    expect(dlg.open).toBeTrue();
    expect(dlg.textContent).toContain('51.40%');
    expect(dlg.textContent).toContain('Blocked');
    expect(dlg.textContent).toContain('add it anyway');
  });

  it('proceed and go back emit separately', () => {
    const f = mount();
    f.componentInstance.g.set(block);
    f.detectChanges();
    const btns = f.nativeElement.querySelectorAll('button') as NodeListOf<HTMLButtonElement>;
    btns[0].click();
    expect(f.componentInstance.no).toBe(1);
    expect(f.componentInstance.yes).toBe(0);
    (f.nativeElement.querySelector('[data-testid=proceed]') as HTMLButtonElement).click();
    expect(f.componentInstance.yes).toBe(1);
  });

  it('WARN offers Continue, not the override wording', () => {
    const f = mount();
    f.componentInstance.g.set(warn);
    f.detectChanges();
    expect(f.nativeElement.textContent).toContain('Continue');
    expect(f.nativeElement.textContent).not.toContain('anyway');
  });
});
