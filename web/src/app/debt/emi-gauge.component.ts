import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { PaiseInrPipe } from '../paise-inr.pipe';
import {
  BLOCK_LINE_PCT, GuardContext, WARN_LINE_PCT, barPercent, ratioPercentText, statusExplanation, statusIcon, statusLabel,
} from './guard-format';
import { GuardView } from './guard.models';

const inr = new PaiseInrPipe();

/** EMI-to-income gauge: filled bar, a marker at the 40% line, one at 50%, and status in words and a symbol. */
@Component({
  selector: 'app-emi-gauge',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './emi-gauge.component.html',
  styleUrl: './emi-gauge.css',
})
export class EmiGaugeComponent {
  readonly guard = input.required<GuardView>();
  readonly context = input<GuardContext>('current');
  readonly warnLine = WARN_LINE_PCT;
  readonly blockLine = BLOCK_LINE_PCT;

  readonly ratioText = computed(() => ratioPercentText(this.guard().ratio));
  readonly fill = computed(() => barPercent(this.guard().ratio));
  readonly label = computed(() => statusLabel(this.guard().status));
  readonly icon = computed(() => statusIcon(this.guard().status));
  readonly explanation = computed(() => statusExplanation(this.guard().status, this.ratioText(), this.context()));
  readonly emi = computed(() => inr.transform(this.guard().totalEmiPaise));
  readonly income = computed(() => inr.transform(this.guard().monthlyIncomePaise));
  readonly headroom = computed(() => inr.transform(this.guard().headroomPaise));
  readonly summary = computed(
    () => `EMI to income ${this.ratioText()}. ${this.label()}. Warning line ${WARN_LINE_PCT}%, block line ${BLOCK_LINE_PCT}%.`,
  );
}
