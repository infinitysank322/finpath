import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { FpTone } from './fp-stat.component';

/**
 * One row of a timeline / list: marker, date block, title (+ subtitle), trailing value.
 *   <div role="list"><fp-timeline-row date="Dec" dateSub="2026" title="..." value="+..." tone="accent" /></div>
 * The host is role="listitem": wrap rows in an element with role="list" (or a <ul role="list"> with display:contents).
 * All text is supplied already formatted. The marker is a filled dot for accent / neutral and a hollow ring for
 * warn / breach, so tone is not carried by colour alone; screen readers get the same words as sighted users because
 * the date, title and value are real text.
 */
@Component({
  selector: 'fp-timeline-row',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { role: 'listitem', '[attr.data-tone]': 'tone()' },
  template: `
    <span class="dot" aria-hidden="true"></span>
    <span class="date">
      <span>{{ date() }}</span>
      @if (dateSub()) {
        <span class="sub">{{ dateSub() }}</span>
      }
    </span>
    <span class="body">
      <span class="title">{{ title() }}</span>
      @if (subtitle()) {
        <span class="sub">{{ subtitle() }}</span>
      }
    </span>
    @if (value()) {
      <span class="value fp-num">{{ value() }}</span>
    }
  `,
  styles: `
    :host { display: flex; align-items: center; gap: var(--fp-space-3); min-width: 0; --c: var(--fp-accent-text); }
    :host([data-tone='neutral']) { --c: var(--fp-text); }
    :host([data-tone='warn']) { --c: var(--fp-warn-text); }
    :host([data-tone='breach']) { --c: var(--fp-breach-text); }
    .dot { flex: none; width: 10px; height: 10px; box-sizing: border-box; border-radius: 50%; background: var(--c); }
    :host([data-tone='warn']) .dot, :host([data-tone='breach']) .dot { background: none; border: 2px solid var(--c); }
    .date { flex: none; width: 4rem; display: flex; flex-direction: column; font-size: var(--fp-fs-xs); color: var(--fp-text-muted); line-height: 1.25; }
    .body { flex: 1; min-width: 0; display: flex; flex-direction: column; overflow-wrap: anywhere; }
    .title { font-size: var(--fp-fs-md); font-weight: 600; color: var(--fp-text); }
    .sub { font-size: var(--fp-fs-xs); color: var(--fp-text-muted); }
    .value { flex: none; font-size: var(--fp-fs-sm); font-weight: 700; color: var(--c); }
  `,
})
export class FpTimelineRowComponent {
  readonly date = input.required<string>();
  readonly dateSub = input<string>('');
  readonly title = input.required<string>();
  readonly subtitle = input<string>('');
  readonly value = input<string>('');
  readonly tone = input<FpTone>('accent');
}
