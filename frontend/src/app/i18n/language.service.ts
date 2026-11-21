import { DOCUMENT } from '@angular/common';
import { Injectable, inject, signal } from '@angular/core';
import { Language, TranslationKey, translations } from './translations';

@Injectable({ providedIn: 'root' })
export class LanguageService {
  private readonly document = inject(DOCUMENT);
  private readonly selected = signal<Language>('fr');
  readonly language = this.selected.asReadonly();

  constructor() {
    let saved: string | null = null;
    try {
      saved = this.document.defaultView?.localStorage.getItem('docukore.language') ?? null;
    } catch {
      /* Storage may be unavailable. */
    }
    this.select(saved === 'en' || saved === 'ar' ? saved : 'fr');
  }

  text(key: TranslationKey): string {
    return translations[this.selected()][key];
  }

  select(language: string): void {
    if (language !== 'fr' && language !== 'en' && language !== 'ar') return;
    this.selected.set(language);
    this.document.documentElement.lang = language;
    this.document.documentElement.dir = language === 'ar' ? 'rtl' : 'ltr';
    try {
      this.document.defaultView?.localStorage.setItem('docukore.language', language);
    } catch {
      /* Preference is optional. */
    }
  }
}
