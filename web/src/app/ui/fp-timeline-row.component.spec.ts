import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { FpTimelineRowComponent } from './fp-timeline-row.component';
import { setInputs } from './ui-testing';

@Component({
  standalone: true,
  imports: [FpTimelineRowComponent],
  template: `<div role="list"><fp-timeline-row date="Mon" dateSub="Year" title="First" /><fp-timeline-row date="Tue" title="Second" /></div>`,
})
class ListHost {}

describe('FpTimelineRowComponent', () => {
  function make(inputs: Record<string, unknown> = {}) {
    const f = TestBed.createComponent(FpTimelineRowComponent);
    setInputs(f, { date: 'Mon', title: 'Row title', ...inputs });
    return f;
  }

  it('renders date, title, subtitle and value as real text', () => {
    const el: HTMLElement = make({ dateSub: 'Year', subtitle: 'Sub', value: '+1,234' }).nativeElement;
    expect(Array.from(el.querySelectorAll('.date > span')).map((s) => s.textContent)).toEqual(['Mon', 'Year']);
    expect(el.querySelector('.title')?.textContent).toBe('Row title');
    expect(el.querySelector('.body .sub')?.textContent).toBe('Sub');
    expect(el.querySelector('.value')?.textContent).toBe('+1,234');
  });

  it('omits optional parts when not given', () => {
    const el: HTMLElement = make().nativeElement;
    expect(el.querySelector('.value')).toBeNull();
    expect(el.querySelector('.body .sub')).toBeNull();
  });

  it('is a list item inside a role=list wrapper', () => {
    const f = TestBed.createComponent(ListHost);
    f.detectChanges();
    const rows = f.nativeElement.querySelectorAll('[role=list] > fp-timeline-row[role=listitem]');
    expect(rows.length).toBe(2);
  });

  it('warn and breach rows use a hollow ring marker, accent a filled dot (shape, not just colour)', () => {
    const f = make({ tone: 'accent' });
    const dot = f.nativeElement.querySelector('.dot') as HTMLElement;
    expect(getComputedStyle(dot).borderTopWidth).toBe('0px');
    setInputs(f, { tone: 'warn' });
    expect(getComputedStyle(dot).borderTopWidth).toBe('2px');
    setInputs(f, { tone: 'breach' });
    expect(getComputedStyle(dot).borderTopWidth).toBe('2px');
  });
});
