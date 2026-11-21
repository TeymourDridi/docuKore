import { Component, DestroyRef, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { AuthService } from './auth.service';
import { LanguageService } from '../i18n/language.service';

@Component({
  selector: 'app-entry',
  template: ` <section class="auth-card" aria-live="polite">
    <h1>{{ i18n.text(failed() ? 'unavailable' : 'loading') }}</h1>
    @if (failed()) {
      <button class="primary" type="button" (click)="open()">{{ i18n.text('retry') }}</button>
    }
  </section>`,
})
export class Entry {
  protected readonly i18n = inject(LanguageService);
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);
  protected readonly failed = signal(false);

  constructor() {
    this.open();
  }

  protected open(): void {
    this.failed.set(false);
    this.auth
      .load()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => {
          void this.router.navigateByUrl(
            this.auth.account() ? '/workspace' : this.auth.setupAvailable() ? '/setup' : '/login'
          );
        },
        error: () => this.failed.set(true),
      });
  }
}
