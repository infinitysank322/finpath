import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { errorMessage } from '../plan/plan-format';
import { EmiGaugeComponent } from './emi-gauge.component';
import { GuardApiService } from './guard-api.service';
import { GuardView } from './guard.models';
import { FpPageHeaderComponent } from '../ui/fp-page-header.component';
import { FpProfileLinkComponent } from '../ui/fp-profile-link.component';

/** FR-18 page: where the user's EMIs stand against the 40% line. */
@Component({
  selector: 'app-emi-guard',
  standalone: true,
  imports: [FpPageHeaderComponent, FpProfileLinkComponent, EmiGaugeComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './emi-guard.component.html',
  styleUrl: './emi-guard.css',
})
export class EmiGuardComponent {
  private readonly api = inject(GuardApiService);
  readonly guard = signal<GuardView | null>(null);
  readonly loading = signal(true);
  readonly error = signal('');

  constructor() {
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.error.set('');
    this.api.current().subscribe({
      next: (g) => {
        this.guard.set(g);
        this.loading.set(false);
      },
      error: (e: HttpErrorResponse) => {
        this.guard.set(null);
        this.loading.set(false);
        this.error.set(errorMessage(e.status, 'Loading the EMI check'));
      },
    });
  }
}
