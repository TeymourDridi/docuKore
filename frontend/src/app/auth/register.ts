import { Component, DestroyRef, OnInit, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { finalize } from 'rxjs';
import { AuthService, RegistrationResult, SetupStatus, SignupRequest } from './auth.service';
import {
  emailAddress,
  passwordConfirmation,
  passwordPolicy,
  requiredName,
  requiredEmailAddress,
  stripRegistrationWhitespace,
} from './auth-validators';
import { registrationError } from './registration-errors';
import { LanguageService } from '../i18n/language.service';
import { TranslationKey } from '../i18n/translations';

@Component({
  selector: 'app-register',
  imports: [ReactiveFormsModule, RouterLink],
  templateUrl: './register.html',
  styleUrl: './registration.scss',
})
export class Register implements OnInit {
  protected readonly i18n = inject(LanguageService);
  private readonly auth = inject(AuthService);
  private readonly destroyRef = inject(DestroyRef);
  protected readonly settings = signal<SetupStatus | null>(null);
  protected readonly loading = signal(false);
  protected readonly loadError = signal<TranslationKey | null>(null);
  protected readonly busy = signal(false);
  protected readonly error = signal<TranslationKey | null>(null);
  protected readonly result = signal<RegistrationResult | null>(null);
  protected readonly registrationEmail = signal<string | null>(null);
  protected readonly resendBusy = signal(false);
  protected readonly resendAccepted = signal(false);
  protected readonly resendError = signal<TranslationKey | null>(null);
  protected readonly passwordVisible = signal(false);
  protected readonly confirmationVisible = signal(false);
  protected readonly form = inject(FormBuilder).nonNullable.group(
    {
      fullName: ['', requiredName],
      cabinetName: ['', requiredName],
      username: [''],
      email: ['', emailAddress],
      password: ['', passwordPolicy],
      confirmation: ['', Validators.required],
    },
    { validators: passwordConfirmation }
  );
  private submittedFingerprint: string | null = null;
  private idempotencyKey: string | null = null;

  ngOnInit(): void {
    this.loadSettings();
  }

  protected loadSettings(): void {
    if (this.loading()) return;
    this.loadError.set(null);
    this.loading.set(true);
    this.auth
      .registrationSettings()
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        finalize(() => this.loading.set(false))
      )
      .subscribe({
        next: (settings) => {
          if (settings.deploymentMode !== 'LOCAL' && settings.deploymentMode !== 'HOSTED') {
            this.loadError.set('registrationUnavailable');
            return;
          }
          this.form.controls.username.setValidators(
            settings.deploymentMode === 'LOCAL'
              ? [Validators.required, Validators.pattern(/^[a-z0-9][a-z0-9._-]{2,63}$/i)]
              : []
          );
          this.form.controls.email.setValidators(
            settings.deploymentMode === 'HOSTED' ? [requiredEmailAddress] : [emailAddress]
          );
          this.form.controls.username.updateValueAndValidity();
          this.form.controls.email.updateValueAndValidity();
          this.settings.set(settings);
        },
        error: (error: unknown) => this.loadError.set(registrationError(error)),
      });
  }

  protected invalidField(name: keyof typeof this.form.controls): boolean {
    const control = this.form.controls[name];
    return control.touched && control.invalid;
  }

  protected confirmationInvalid(): boolean {
    return (
      this.form.controls.confirmation.touched &&
      (this.form.controls.confirmation.invalid || this.form.hasError('passwordConfirmation'))
    );
  }

  protected submit(): void {
    const settings = this.settings();
    if (this.busy() || this.result() || !settings) return;
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      this.error.set('registrationInvalidInput');
      return;
    }
    const input = this.form.getRawValue();
    const payload = {
      username:
        settings.deploymentMode === 'LOCAL' ? stripRegistrationWhitespace(input.username) : null,
      email: stripRegistrationWhitespace(input.email).toLowerCase() || null,
      fullName: stripRegistrationWhitespace(input.fullName).normalize('NFC'),
      cabinetName: stripRegistrationWhitespace(input.cabinetName).normalize('NFC'),
      password: input.password,
    };
    const fingerprint = JSON.stringify(payload);
    if (fingerprint !== this.submittedFingerprint || this.idempotencyKey === null) {
      this.idempotencyKey = crypto.randomUUID();
      this.submittedFingerprint = fingerprint;
    }
    const request: SignupRequest = { ...payload, idempotencyKey: this.idempotencyKey };
    this.error.set(null);
    this.busy.set(true);
    this.auth
      .signup(request)
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        finalize(() => this.busy.set(false))
      )
      .subscribe({
        next: (result) => {
          this.registrationEmail.set(payload.email);
          this.result.set(result);
          this.form.controls.password.reset();
          this.form.controls.confirmation.reset();
          this.passwordVisible.set(false);
          this.confirmationVisible.set(false);
          this.submittedFingerprint = null;
          this.idempotencyKey = null;
        },
        error: (error: unknown) => this.error.set(registrationError(error)),
      });
  }

  protected resend(): void {
    const email = this.registrationEmail();
    const status = this.result()?.status;
    if (
      this.resendBusy() ||
      !email ||
      (status !== 'PENDING_EMAIL_VERIFICATION' && status !== 'DELIVERY_FAILED')
    )
      return;
    this.resendError.set(null);
    this.resendAccepted.set(false);
    this.resendBusy.set(true);
    this.auth
      .resendVerification(email)
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        finalize(() => this.resendBusy.set(false))
      )
      .subscribe({
        next: () => this.resendAccepted.set(true),
        error: (error: unknown) => this.resendError.set(registrationError(error)),
      });
  }
}
