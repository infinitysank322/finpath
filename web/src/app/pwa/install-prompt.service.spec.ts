import { TestBed } from '@angular/core/testing';
import { InstallPromptService } from './install-prompt.service';

describe('InstallPromptService', () => {
  it('captures the browser offer, prompts once, then clears', async () => {
    const svc = TestBed.inject(InstallPromptService);
    expect(svc.canInstall()).toBeFalse();
    const prompt = jasmine.createSpy('prompt').and.resolveTo();
    const ev = Object.assign(new Event('beforeinstallprompt', { cancelable: true }), {
      prompt,
      userChoice: Promise.resolve({ outcome: 'accepted' as const }),
    });
    window.dispatchEvent(ev);
    expect(ev.defaultPrevented).toBeTrue();
    expect(svc.canInstall()).toBeTrue();
    await svc.install();
    expect(prompt).toHaveBeenCalledTimes(1);
    expect(svc.canInstall()).toBeFalse();
    await svc.install();
    expect(prompt).toHaveBeenCalledTimes(1);
  });
});
