import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterLink } from '@angular/router';
import { FpPageHeaderComponent } from '../ui/fp-page-header.component';
import { FpProfileLinkComponent } from '../ui/fp-profile-link.component';

/** Entry to the debt screens. Links only; no figures. */
@Component({
  selector: 'app-debt-hub',
  standalone: true,
  imports: [RouterLink, FpPageHeaderComponent, FpProfileLinkComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: [`a.fp-row { min-height: var(--fp-target); color: var(--fp-text); text-decoration: none; font-weight: 600; }`],
  template: `
    <div class="fp-page">
      <fp-page-header title="Debt"><fp-profile-link fpTrailing /></fp-page-header>
      <nav aria-label="Debt screens">
        <ul class="fp-list">
          <li><a class="fp-row" routerLink="/debt/loans">Your loans</a></li>
          <li><a class="fp-row" routerLink="/debt/emi-guard">EMI to income</a></li>
          <li><a class="fp-row" routerLink="/debt/payoff">Payoff timeline</a></li>
        </ul>
      </nav>
    </div>
  `,
})
export class DebtHubComponent {}
