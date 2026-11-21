import { Component, DestroyRef, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router } from '@angular/router';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { finalize } from 'rxjs';
import { AuthService } from './auth.service';
import { authError } from './auth-errors';
import { LanguageService } from '../i18n/language.service';
import { TranslationKey } from '../i18n/translations';

import { passwordPolicy } from './auth-validators';

@Component({ selector: 'app-setup', imports: [ReactiveFormsModule], templateUrl: './setup.html' })
export class Setup {
  protected readonly i18n = inject(LanguageService);
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);
  protected readonly busy = signal(false);
  protected readonly error = signal<TranslationKey | null>(null);
  protected readonly form = inject(FormBuilder).nonNullable.group({
    fullName: ['', [Validators.required, Validators.maxLength(255)]],
    username: ['', [Validators.required, Validators.pattern('[A-Za-z0-9][A-Za-z0-9._-]{2,63}')]],
    password: ['', passwordPolicy],
    confirmation: ['', Validators.required],
  });

  protected submit(): void {
    if (this.busy()) return;
    const input = this.form.getRawValue();
    if (this.form.invalid || input.password !== input.confirmation || !input.fullName.trim()) {
      this.form.markAllAsTouched();
      this.error.set('invalidInput');
      return;
    }
    this.error.set(null);
    this.busy.set(true);
    this.auth
      .setup({ username: input.username, fullName: input.fullName, password: input.password })
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        finalize(() => this.busy.set(false))
      )
      .subscribe({
        next: () => {
          this.form.reset();
          void this.router.navigate(['/login'], { queryParams: { created: 1 } });
        },
        error: (error: unknown) => {
          this.form.controls.password.reset();
          this.form.controls.confirmation.reset();
          this.error.set(authError(error));
        },
      });
  }
}
