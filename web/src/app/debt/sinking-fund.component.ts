import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { errorMessage } from '../plan/plan-format';
import { exactInr } from './loan-interest-format';
import { rupeesToPaise } from './prepay-format';
import { SinkingFundApiService } from './sinking-fund-api.service';
import { statusText } from './sinking-fund-format';
import { SinkingFundView } from './sinking-fund.models';
import { FpPageHeaderComponent } from '../ui/fp-page-header.component';
import { FpProfileLinkComponent } from '../ui/fp-profile-link.component';

@Component({
  selector: 'app-sinking-fund',
  standalone: true,
  imports: [FpPageHeaderComponent, FpProfileLinkComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './sinking-fund.component.html',
  styleUrl: './sinking-fund.css',
})
export class SinkingFundComponent {
  private readonly api = inject(SinkingFundApiService);
  private readonly id = inject(ActivatedRoute).snapshot.paramMap.get('id') ?? '';
  /** Every figure shown is exact to the paise and comes from the API. */
  readonly fmt = exactInr;
  readonly label = statusText;

  readonly saved = signal('0');
  /** The paise the user typed, echoed beside the API's figures; no arithmetic on it. */
  readonly savedPaise = signal(0);
  readonly fund = signal<SinkingFundView | null>(null);
  readonly loading = signal(false);
  readonly error = signal('');

  load(): void {
    if (this.loading()) return;
    const paise = rupeesToPaise(this.saved());
    if (paise === null) {
      this.error.set('Enter the amount set aside in rupees, as a number.');
      this.fund.set(null);
      return;
    }
    this.loading.set(true);
    this.error.set('');
    this.api.status(this.id, paise).subscribe({
      next: (f) => {
        this.savedPaise.set(paise);
        this.fund.set(f);
        this.loading.set(false);
      },
      error: (e: HttpErrorResponse) => {
        this.fund.set(null);
        this.loading.set(false);
        this.error.set(
          e.status === 400
            ? 'A sinking fund applies only to a bullet loan with a due date.'
            : errorMessage(e.status, 'Checking the fund'),
        );
      },
    });
  }
}
