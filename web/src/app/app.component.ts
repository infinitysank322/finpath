import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { ThemeService } from './theme.service';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './app.component.html',
  styleUrl: './app.component.css',
})
export class AppComponent {
  readonly themeService = inject(ThemeService);
  readonly nav = [
    { path: '/home', label: 'Home', icon: 'M3 11l9-8 9 8v10h-6v-6H9v6H3z' },
    { path: '/spending', label: 'Spending', icon: 'M4 20V10h4v10zm6 0V4h4v16zm6 0v-7h4v7z' },
    { path: '/debt', label: 'Debt', icon: 'M3 6h18v12H3zm3 3v6h12V9z' },
    { path: '/goals', label: 'Goals', icon: 'M12 2a10 10 0 100 20 10 10 0 000-20zm0 5a5 5 0 110 10 5 5 0 010-10zm0 3a2 2 0 100 4 2 2 0 000-4z' },
    { path: '/more', label: 'More', icon: 'M5 10a2 2 0 100 4 2 2 0 000-4zm7 0a2 2 0 100 4 2 2 0 000-4zm7 0a2 2 0 100 4 2 2 0 000-4z' },
  ];
}
