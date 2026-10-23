import { ChangeDetectionStrategy, Component, ElementRef, computed, effect, input, output, viewChild } from '@angular/core';
import { EmiGaugeComponent } from './emi-gauge.component';
import { GuardView } from './guard.models';

/**
 * Shown when adding a loan would push EMIs past a guard line. WARN may proceed; BLOCK proceeds only through
 * the explicit second action (the client then re-sends with confirmBlock). Cancel is the default focus.
 * Wiring into the loan create flow (and the confirmBlock call) is T55. The parent must set `guard` back to null
 * after `confirmed` or `cancelled`; an OK guard never opens the dialog.
 */
@Component({
  selector: 'app-guard-dialog',
  standalone: true,
  imports: [EmiGaugeComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './guard-dialog.component.html',
  styleUrl: './guard-dialog.css',
})
export class GuardDialogComponent {
  /** The guard result for the loan being added; null keeps the dialog closed. */
  readonly guard = input<GuardView | null>(null);
  readonly confirmed = output<void>();
  readonly cancelled = output<void>();

  private readonly dlg = viewChild<ElementRef<HTMLDialogElement>>('dlg');
  readonly blocked = computed(() => this.guard()?.status === 'BLOCK');

  constructor() {
    effect(() => {
      const el = this.dlg()?.nativeElement;
      const open = this.guard() !== null && this.guard()!.status !== 'OK';
      if (!el) {
        return;
      }
      if (open && !el.open) {
        el.showModal();
      } else if (!open && el.open) {
        el.close();
      }
    });
  }

  onCancel(e: Event): void {
    e.preventDefault();
    this.cancelled.emit();
  }
}
