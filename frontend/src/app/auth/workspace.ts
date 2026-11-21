import { Component, DestroyRef, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { finalize } from 'rxjs';
import { AuthService } from './auth.service';
import { authError } from './auth-errors';
import { LanguageService } from '../i18n/language.service';
import { TranslationKey } from '../i18n/translations';

@Component({ selector: 'app-workspace', templateUrl: './workspace.html' })
export class Workspace {
  protected readonly i18n = inject(LanguageService);
  protected readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);
  protected readonly busy = signal(false);
  protected readonly error = signal<TranslationKey | null>(null);

  protected logout(): void {
    if (this.busy()) return;
    this.busy.set(true);
    this.error.set(null);
    this.auth
      .logout()
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        finalize(() => this.busy.set(false))
      )
      .subscribe({
        next: () => {
          void this.router.navigateByUrl('/login');
        },
        error: (error: unknown) => {
          this.error.set(authError(error));
        },
      });
  }
}
