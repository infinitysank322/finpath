import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { errorMessage } from '../plan/plan-format';
import { exactInr } from './loan-interest-format';
import { PrepayApiService } from './prepay-api.service';
import { buildQuery, cycleEndLabel } from './prepay-format';
import { PrepayMode, PrepaymentView } from './prepay.models';
import { FpPageHeaderComponent } from '../ui/fp-page-header.component';
import { FpProfileLinkComponent } from '../ui/fp-profile-link.component';

@Component({
  selector: 'app-prepay',
  standalone: true,
  imports: [FpPageHeaderComponent, FpProfileLinkComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './prepay.component.html',
  styleUrl: './prepay.css',
})
export class PrepayComponent {
  private readonly api = inject(PrepayApiService);
  private readonly id = inject(ActivatedRoute).snapshot.paramMap.get('id') ?? '';
  /** Every figure shown is exact to the paise and comes from the API. */
  readonly fmt = exactInr;
  readonly endLabel = cycleEndLabel;

  readonly mode = signal<PrepayMode>('lump_sum');
  readonly lump = signal('');
  readonly after = signal('');
  readonly newEmi = signal('');
  readonly months = signal('');

  readonly result = signal<PrepaymentView | null>(null);
  readonly loading = signal(false);
  readonly error = signal('');

  setMode(m: PrepayMode): void {
    this.mode.set(m);
    this.result.set(null);
    this.error.set('');
  }

  run(): void {
    if (this.loading()) return;
    const built = buildQuery({ mode: this.mode(), lump: this.lump(), after: this.after(), newEmi: this.newEmi(), months: this.months() });
    if ('error' in built) {
      this.error.set(built.error);
      this.result.set(null);
      return;
    }
    this.loading.set(true);
    this.error.set('');
    this.api.simulate(this.id, built.query).subscribe({
      next: (r) => {
        this.result.set(r);
        this.loading.set(false);
      },
      error: (e: HttpErrorResponse) => {
        this.result.set(null);
        this.loading.set(false);
        this.error.set(
          e.status === 400
            ? 'This what-if cannot be run. Only loans with an EMI and instalments left can be simulated, and the numbers must be in range.'
            : errorMessage(e.status, 'The simulation'),
        );
      },
    });
  }
}
