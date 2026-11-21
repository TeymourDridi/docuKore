import { Component, DestroyRef, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { finalize } from 'rxjs';
import { AuthService } from './auth.service';
import { authError } from './auth-errors';
import { LanguageService } from '../i18n/language.service';
import { TranslationKey } from '../i18n/translations';

@Component({
  selector: 'app-login',
  imports: [ReactiveFormsModule, RouterLink],
  templateUrl: './login.html',
})
export class Login {
  protected readonly i18n = inject(LanguageService);
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);
  private readonly route = inject(ActivatedRoute);
  protected readonly created = this.route.snapshot.queryParamMap.has('created');
  protected readonly expired = this.route.snapshot.queryParamMap.has('expired');
  protected readonly busy = signal(false);
  protected readonly error = signal<TranslationKey | null>(null);
  protected readonly form = inject(FormBuilder).nonNullable.group({
    username: ['', [Validators.required, Validators.maxLength(255)]],
    password: ['', Validators.required],
  });

  protected submit(): void {
    if (this.busy()) return;
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      this.error.set('invalidInput');
      return;
    }
    this.error.set(null);
    this.busy.set(true);
    const input = this.form.getRawValue();
    this.auth
      .login(input.username, input.password)
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        finalize(() => this.busy.set(false))
      )
      .subscribe({
        next: () => {
          this.form.controls.password.reset();
          void this.router.navigateByUrl('/workspace');
        },
        error: (error: unknown) => {
          this.form.controls.password.reset();
          this.error.set(authError(error, true));
        },
      });
  }
}
