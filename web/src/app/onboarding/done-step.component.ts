import { ChangeDetectionStrategy, Component } from '@angular/core';

@Component({
  selector: 'app-done-step',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<p>Your account is ready. You can add your income, bills and goals any time from the More tab.</p>`,
  styleUrl: './onboarding.css',
})
export class DoneStepComponent {}
