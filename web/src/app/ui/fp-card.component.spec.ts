import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { FpCardComponent } from './fp-card.component';

@Component({
  standalone: true,
  imports: [FpCardComponent],
  template: `
    <fp-card [variant]="variant" [heading]="heading"><p class="body">Inside 1234</p></fp-card>
    <fp-card><span fpCardHeading class="custom">Custom heading</span><p>Body</p></fp-card>
  `,
})
class HostComponent {
  variant: 'default' | 'hero' = 'default';
  heading = 'Section';
}

describe('FpCardComponent', () => {
  function make() {
    const f = TestBed.createComponent(HostComponent);
    f.detectChanges();
    return f;
  }

  it('renders projected content and a section-label heading', () => {
    const el: HTMLElement = make().nativeElement;
    const card = el.querySelector('fp-card') as HTMLElement;
    expect(card.querySelector('.body')?.textContent).toBe('Inside 1234');
    const h = card.querySelector('h2.fp-label') as HTMLElement;
    expect(h.textContent).toBe('Section');
    expect(getComputedStyle(h).textTransform).toBe('uppercase');
  });

  it('projects a custom heading slot and omits the h2 when no heading text is given', () => {
    const el: HTMLElement = make().nativeElement;
    const second = el.querySelectorAll('fp-card')[1];
    expect(second.querySelector('h2')).toBeNull();
    expect(second.querySelector('.custom')?.textContent).toBe('Custom heading');
  });

  it('hero variant adds the gradient, shadow and larger radius; default is flat', () => {
    const f = make();
    const card = f.nativeElement.querySelector('fp-card') as HTMLElement;
    const flat = getComputedStyle(card);
    const flatBg = flat.backgroundImage;
    const flatShadow = flat.boxShadow;
    const flatRadius = parseFloat(getComputedStyle(card).borderTopLeftRadius);
    expect(flatBg).toBe('none');
    expect(flatShadow).toBe('none');
    f.componentInstance.variant = 'hero';
    f.componentRef.changeDetectorRef.markForCheck();
    f.detectChanges();
    const hero = getComputedStyle(card);
    expect(card.classList).toContain('hero');
    expect(hero.backgroundImage).toContain('linear-gradient');
    expect(hero.boxShadow).not.toBe('none');
    expect(parseFloat(hero.borderTopLeftRadius)).toBeGreaterThan(flatRadius);
  });
});
