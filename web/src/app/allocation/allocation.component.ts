import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { PaiseInrPipe } from '../paise-inr.pipe';
import { rupeesToPaise } from '../debt/prepay-format';
import { exactInr } from '../debt/loan-interest-format';
import { errorMessage } from '../plan/plan-format';
import { stepName } from './allocation-format';
import { AllocationApiService } from './allocation-api.service';
import { AllocationView, RebalancePreview } from './allocation.models';
import { FpPageHeaderComponent } from '../ui/fp-page-header.component';

@Component({
  selector: 'app-allocation',
  standalone: true,
  imports: [FpPageHeaderComponent, PaiseInrPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './allocation.component.html',
  styleUrl: './allocation.css',
})
export class AllocationComponent implements OnInit {
  private readonly api = inject(AllocationApiService);
  readonly name = stepName;
  readonly fmt = exactInr;

  readonly view = signal<AllocationView | null>(null);
  readonly loading = signal(false);
  readonly missing = signal(false);
  readonly error = signal('');

  readonly spend = signal('');
  readonly preview = signal<RebalancePreview | null>(null);
  readonly previewing = signal(false);
  readonly previewError = signal('');

  ngOnInit(): void {
    this.loading.set(true);
    this.api.current().subscribe({
      next: (v) => this.done(v),
      error: (e: HttpErrorResponse) => this.fail(e, true),
    });
  }

  run(): void {
    if (this.loading()) return;
    this.loading.set(true);
    this.error.set('');
    this.api.run().subscribe({
      next: (v) => this.done(v),
      error: (e: HttpErrorResponse) => this.fail(e, false),
    });
  }

  check(): void {
    if (this.previewing()) return;
    const paise = rupeesToPaise(this.spend());
    if (paise === null || paise <= 0) {
      this.previewError.set('Enter the unplanned spend in rupees, such as the amount on the receipt.');
      this.preview.set(null);
      return;
    }
    this.previewing.set(true);
    this.previewError.set('');
    this.api.rebalance(paise).subscribe({
      next: (p) => {
        this.preview.set(p);
        this.previewing.set(false);
      },
      error: (e: HttpErrorResponse) => {
        this.preview.set(null);
        this.previewing.set(false);
        this.previewError.set(errorMessage(e.status, 'Checking the spend'));
      },
    });
  }

  private done(v: AllocationView): void {
    this.view.set(v);
    this.missing.set(false);
    this.loading.set(false);
    this.preview.set(null);
  }

  private fail(e: HttpErrorResponse, initial: boolean): void {
    this.loading.set(false);
    if (initial && e.status === 404) {
      this.missing.set(true);
      return;
    }
    this.error.set(errorMessage(e.status, 'Planning this cycle'));
  }
}
