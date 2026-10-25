import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterLink } from '@angular/router';

/** Entry to the debt screens. Links only; no figures. */
@Component({
  selector: 'app-debt-hub',
  standalone: true,
  imports: [RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: [`:host { display: block; max-width: 32rem; margin: 0 auto; } h1 { margin: 0 0 .5rem; font-size: 1.5rem; } a { display: flex; align-items: center; min-height: 44px; color: var(--fp-text); } ul { list-style: none; padding: 0; margin: 0; }`],
  template: `
    <h1>Debt</h1>
    <nav aria-label="Debt screens">
      <ul>
        <li><a routerLink="/debt/loans">Your loans</a></li>
        <li><a routerLink="/debt/emi-guard">EMI to income</a></li>
        <li><a routerLink="/debt/payoff">Payoff timeline</a></li>
      </ul>
    </nav>
  `,
})
export class DebtHubComponent {}
