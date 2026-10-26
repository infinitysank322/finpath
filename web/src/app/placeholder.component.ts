import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { FpCardComponent } from './ui/fp-card.component';
import { FpPageHeaderComponent } from './ui/fp-page-header.component';
import { FpProfileLinkComponent } from './ui/fp-profile-link.component';

@Component({
  selector: 'app-placeholder',
  standalone: true,
  imports: [FpPageHeaderComponent, FpProfileLinkComponent, FpCardComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="fp-page">
      <fp-page-header [title]="heading"><fp-profile-link fpTrailing /></fp-page-header>
      <fp-card><p class="muted">This screen is not built yet.</p></fp-card>
    </div>
  `,
  styles: [`p { margin: 0; }`],
})
export class PlaceholderComponent {
  readonly heading: string = inject(ActivatedRoute).snapshot.data['heading'] ?? '';
}
