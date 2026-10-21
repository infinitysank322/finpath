import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { ActivatedRoute } from '@angular/router';

@Component({
  selector: 'app-placeholder',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <h1>{{ heading }}</h1>
    <p class="muted">This screen is not built yet.</p>
  `,
  styles: [`h1 { margin: 0 0 .5rem; font-size: 1.5rem; }`],
})
export class PlaceholderComponent {
  readonly heading: string = inject(ActivatedRoute).snapshot.data['heading'] ?? '';
}
