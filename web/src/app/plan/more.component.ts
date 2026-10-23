import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterLink } from '@angular/router';

@Component({
  selector: 'app-more',
  standalone: true,
  imports: [RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <h1>More</h1>
    <nav aria-label="Plan setup">
      <ul>
        <li><a routerLink="/more/income">Income sources</a></li>
        <li><a routerLink="/more/recurring">Recurring bills</a></li>
        <li><a routerLink="/spending/budget">Category budgets</a></li>
        <li><a routerLink="/goals/fi-planner">FI planner</a></li>
        <li><a routerLink="/more/allocation">Cycle allocation</a></li>
      </ul>
    </nav>
  `,
  styles: [
    `h1 { margin: 0 0 .5rem; font-size: 1.5rem; }
     ul { list-style: none; margin: 0; padding: 0; }
     li { margin-bottom: .5rem; }
     a { display: block; min-height: 44px; padding: .625rem .75rem; box-sizing: border-box; background: var(--fp-surface); border: 1px solid var(--fp-border); border-radius: 8px; color: var(--fp-primary-text); }`,
  ],
})
export class MoreComponent {}
