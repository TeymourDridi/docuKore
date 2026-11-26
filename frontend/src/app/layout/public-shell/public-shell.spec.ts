import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { LanguageService } from '../../i18n/language.service';
import { PublicShell } from './public-shell';

describe('PublicShell', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [PublicShell], providers: [provideRouter([])] });
    TestBed.inject(LanguageService).select('fr');
  });

  afterEach(() => TestBed.inject(LanguageService).select('fr'));

  it('retains public branding, the footer and an outlet for authentication pages', async () => {
    const fixture = TestBed.createComponent(PublicShell);
    await fixture.whenStable();
    const element: HTMLElement = fixture.nativeElement;
    expect(element.querySelector('header')?.textContent).toContain('Docukore');
    expect(element.querySelector('main router-outlet')).not.toBeNull();
    expect(element.querySelector('footer')?.textContent).toContain('Des données confidentielles');
    expect(element.querySelector('select')?.getAttribute('aria-label')).toBe('Langue');
  });

  it('switches translated copy and document direction through the language selector', async () => {
    const fixture = TestBed.createComponent(PublicShell);
    await fixture.whenStable();
    const element: HTMLElement = fixture.nativeElement;
    const select = element.querySelector('select')!;
    for (const [language, direction, label] of [
      ['en', 'ltr', 'Language'],
      ['ar', 'rtl', 'اللغة'],
      ['fr', 'ltr', 'Langue'],
    ]) {
      select.value = language;
      select.dispatchEvent(new Event('change'));
      await fixture.whenStable();
      expect(document.documentElement.lang).toBe(language);
      expect(document.documentElement.dir).toBe(direction);
      expect(select.getAttribute('aria-label')).toBe(label);
      expect(select.value).toBe(language);
    }
  });
});
