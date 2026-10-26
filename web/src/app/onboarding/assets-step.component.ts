import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import { formatExactPaise } from '../expense/amount-entry';
import { rupeesTextToPaiseOrZero } from '../goals/goal-format';
import { HoldingApiService } from '../investments/holding-api.service';
import { classLabel, validName } from '../investments/holding-format';
import { ASSET_CLASSES, AssetClass, HoldingView, NAME_MAX, NAME_WARNING } from '../investments/holding.models';
import { errorMessage, paiseToRupeesText } from '../plan/plan-format';
import { PlanStepsState } from './plan-steps.state';

const EPF_NAME = 'EPF';

/**
 * Optional wizard step. [?OQ-1] The EPF balance is a field the user types (default 0); nothing here estimates it.
 * Other holdings can be added with a label, class and current value; the Investments screen has the full form.
 */
@Component({
  selector: 'app-assets-step',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <p class="muted">Educational only, not advice. Skip this if you are not ready; you can add holdings any time under More, Investments.</p>
    <label for="epf-balance">EPF balance (rupees)</label>
    <input id="epf-balance" type="text" inputmode="decimal" autocomplete="off" aria-describedby="epf-hint" [value]="epfText()" (input)="onEpf($event)" />
    <p id="epf-hint" class="muted small">Type the balance from your EPF passbook or statement. We never work it out for you. Leave it at 0 if you do not know it.</p>
    <button type="button" class="btn" [disabled]="!canSaveEpf()" [attr.aria-busy]="savingEpf()" (click)="saveEpf()">{{ epfId() ? 'Update EPF balance' : 'Save EPF balance' }}</button>

    <label for="asset-class">Another holding: type</label>
    <select id="asset-class" (change)="assetClass.set(asClass(val($event)))">
      @for (c of otherClasses; track c.code) {
        <option [value]="c.code" [selected]="c.code === assetClass()">{{ c.label }}</option>
      }
    </select>
    <label for="asset-name">Label (1 to {{ nameMax }} characters)</label>
    <input id="asset-name" type="text" autocomplete="off" [attr.maxlength]="nameMax" aria-describedby="asset-hint" [value]="name()" (input)="name.set(val($event))" />
    <label for="asset-value">Current value (rupees)</label>
    <input id="asset-value" type="text" inputmode="decimal" autocomplete="off" aria-describedby="asset-hint" [value]="valueText()" (input)="valueText.set(val($event))" />
    <p id="asset-hint" class="muted small">{{ nameWarning }}</p>
    <button type="button" class="btn" [disabled]="!canAdd()" [attr.aria-busy]="saving()" (click)="add()">Add holding</button>

    @if (items().length > 0) {
      <ul class="rows" aria-label="Holdings added">
        @for (h of items(); track h.id) {
          <li>{{ h.name }} ({{ label(h.assetClass) }}): {{ exact(h.currentValuePaise) }}</li>
        }
      </ul>
    }
    <p class="error" role="alert">{{ error() }}</p>
  `,
  styleUrl: './onboarding.css',
})
export class AssetsStepComponent implements OnInit {
  private readonly api = inject(HoldingApiService);
  private readonly state = inject(PlanStepsState);
  readonly nameMax = NAME_MAX;
  readonly nameWarning = NAME_WARNING;
  readonly exact = formatExactPaise;
  readonly label = classLabel;
  /** EPF has its own field above, so it is not offered in the generic list. */
  readonly otherClasses = ASSET_CLASSES.filter((c) => c.code !== 'epf');

  readonly items = signal<HoldingView[]>([]);
  readonly epfText = signal('0');
  /** The existing EPF holding, if the user already has one; saving then replaces it instead of adding a second. */
  readonly epfId = signal<string | null>(null);
  readonly assetClass = signal<AssetClass>('mf');
  readonly name = signal('');
  readonly valueText = signal('');
  readonly saving = signal(false);
  readonly savingEpf = signal(false);
  readonly error = signal('');

  readonly epfPaise = computed(() => (this.epfText().trim() === '' ? 0 : rupeesTextToPaiseOrZero(this.epfText())));
  readonly valuePaise = computed(() => (this.valueText().trim() === '' ? 0 : rupeesTextToPaiseOrZero(this.valueText())));
  readonly canSaveEpf = computed(() => this.epfPaise() !== null && !this.savingEpf());
  readonly canAdd = computed(() => validName(this.name()) && this.valuePaise() !== null && !this.saving());
  private epfTouched = false;

  ngOnInit(): void {
    this.reload();
  }

  asClass(v: string): AssetClass {
    return this.otherClasses.find((c) => c.code === v)?.code ?? 'mf';
  }

  onEpf(e: Event): void {
    this.epfTouched = true;
    this.epfText.set(this.val(e));
  }

  val(e: Event): string {
    return (e.target as HTMLInputElement).value;
  }

  private reload(): void {
    this.api.list().subscribe({
      next: (rows) => {
        this.items.set(rows);
        this.state.holdingCount.set(rows.length);
        const epf = rows.find((r) => r.assetClass === 'epf');
        this.epfId.set(epf?.id ?? null);
        if (epf && !this.epfTouched) this.epfText.set(paiseToRupeesText(epf.currentValuePaise));
      },
      error: (e: HttpErrorResponse) => this.error.set(errorMessage(e.status, 'Loading holdings')),
    });
  }

  saveEpf(): void {
    const currentValuePaise = this.epfPaise();
    if (currentValuePaise === null || !this.canSaveEpf()) return;
    const existing = this.items().find((r) => r.id === this.epfId());
    const body = {
      assetClass: 'epf' as const,
      name: existing?.name ?? EPF_NAME,
      investedPaise: existing?.investedPaise ?? 0,
      currentValuePaise,
      monthlyContributionPaise: existing?.monthlyContributionPaise ?? 0,
    };
    this.savingEpf.set(true);
    this.error.set('');
    const id = this.epfId();
    (id ? this.api.replace(id, body) : this.api.create(body)).subscribe({
      next: () => {
        this.savingEpf.set(false);
        this.epfTouched = false;
        this.reload();
      },
      error: (e: HttpErrorResponse) => {
        this.savingEpf.set(false);
        this.error.set(errorMessage(e.status, 'Saving the EPF balance'));
      },
    });
  }

  add(): void {
    const currentValuePaise = this.valuePaise();
    if (currentValuePaise === null || !this.canAdd()) return;
    this.saving.set(true);
    this.error.set('');
    this.api
      .create({ assetClass: this.assetClass(), name: this.name().trim(), investedPaise: 0, currentValuePaise, monthlyContributionPaise: 0 })
      .subscribe({
        next: () => {
          this.saving.set(false);
          this.name.set('');
          this.valueText.set('');
          this.reload();
        },
        error: (e: HttpErrorResponse) => {
          this.saving.set(false);
          this.error.set(errorMessage(e.status, 'Saving the holding'));
        },
      });
  }
}
