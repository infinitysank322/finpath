import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import { ConsentService } from './consent.service';

@Component({
  selector: 'app-consent-step',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (consent.state(); as s) {
      <p class="muted">Why we ask: {{ s.statement.purpose }}</p>
      <p class="statement">{{ s.statement.text }}</p>
      <p class="muted small">Wording version {{ s.statement.version }}</p>
      @if (consent.given()) {
        <p class="ok" role="status">Thank you. Your consent is recorded. You can continue.</p>
      } @else {
        <label class="check">
          <input type="checkbox" [checked]="agreed()" (change)="toggle()" />
          <span>I have read this and I agree.</span>
        </label>
        <p class="muted small" id="consent-hint">Give consent to continue.</p>
        <button type="button" class="fp-btn" [disabled]="!agreed() || consent.busy()" [attr.aria-busy]="consent.busy()" (click)="grant()">
          Give consent
        </button>
      }
    } @else if (consent.loading()) {
      <p role="status">Loading...</p>
    }
    <p class="error" role="alert">{{ consent.error() }}</p>
    @if (!consent.state() && !consent.loading()) {
      <button type="button" class="fp-pill" (click)="consent.refresh()">Try again</button>
    }
  `,
  styleUrl: './onboarding.css',
})
export class ConsentStepComponent implements OnInit {
  readonly consent = inject(ConsentService);
  /** The statement version the box was ticked for; a new wording needs a fresh tick. */
  private readonly agreedVersion = signal<string | null>(null);
  readonly agreed = computed(() => {
    const v = this.consent.state()?.statement.version;
    return v !== undefined && this.agreedVersion() === v;
  });

  toggle(): void {
    this.agreedVersion.set(this.agreed() ? null : (this.consent.state()?.statement.version ?? null));
  }

  ngOnInit(): void {
    void this.consent.refresh();
  }

  grant(): void {
    void this.consent.grant();
  }
}
