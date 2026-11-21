import { HttpErrorResponse } from '@angular/common/http';
import { Component, DestroyRef, OnDestroy, OnInit, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { LanguageService } from '../i18n/language.service';
import { TranslationKey } from '../i18n/translations';
import { AuthService, RegistrationResult } from './auth.service';

type VerificationState =
  | { readonly kind: 'verifying' | 'active' | 'pending' }
  | { readonly kind: 'invalid'; readonly message: 'verificationMissing' | 'verificationInvalid' }
  | {
      readonly kind: 'unavailable';
      readonly message: 'verificationUnavailable' | 'csrfError';
      readonly retryable: boolean;
    };

@Component({
  selector: 'app-verify-email',
  imports: [RouterLink],
  templateUrl: './verify-email.html',
  styleUrl: './verify-email.scss',
})
export class VerifyEmail implements OnInit, OnDestroy {
  protected readonly i18n = inject(LanguageService);
  private readonly auth = inject(AuthService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);
  private token: string | null = null;

  protected readonly state = signal<VerificationState>({
    kind: 'invalid',
    message: 'verificationMissing',
  });
  protected readonly busy = computed(() => this.state().kind === 'verifying');
  protected readonly retryable = computed(() => {
    const state = this.state();
    return state.kind === 'unavailable' && state.retryable;
  });
  protected readonly title = computed<TranslationKey>(() => {
    switch (this.state().kind) {
      case 'active':
        return 'verifiedTitle';
      case 'pending':
        return 'verificationPendingTitle';
      default:
        return 'verifyTitle';
    }
  });
  protected readonly message = computed<TranslationKey>(() => {
    const state = this.state();
    switch (state.kind) {
      case 'active':
        return 'verifiedIntro';
      case 'pending':
        return 'verificationPendingIntro';
      case 'verifying':
        return 'verifyIntro';
      default:
        return state.message;
    }
  });

  ngOnInit(): void {
    // Read once: clearing the fragment or rerendering must never replay this proof.
    const fragment = this.route.snapshot.fragment;
    const tokens = new URLSearchParams(fragment ?? '').getAll('token');
    const token = tokens.length === 1 ? tokens[0] : null;

    // Replace, rather than append, the current history entry before sending the proof.
    if (fragment !== null) {
      void this.router.navigate([], {
        relativeTo: this.route,
        queryParamsHandling: 'preserve',
        preserveFragment: false,
        replaceUrl: true,
      });
    }

    if (tokens.length === 0 || (tokens.length === 1 && !token)) {
      this.state.set({ kind: 'invalid', message: 'verificationMissing' });
      return;
    }
    if (!token || token.length !== 43 || !/^[A-Za-z0-9_-]{43}$/.test(token)) {
      this.state.set({ kind: 'invalid', message: 'verificationInvalid' });
      return;
    }
    this.token = token;
    this.verify();
  }

  ngOnDestroy(): void {
    this.token = null;
  }

  protected retry(): void {
    if (!this.retryable()) return;
    this.verify();
  }

  private verify(): void {
    if (this.busy() || !this.token) return;
    this.state.set({ kind: 'verifying' });
    this.auth
      .verifyEmail(this.token)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (result: RegistrationResult) => {
          // Even an unexpected successful response must not leave a reusable proof.
          this.token = null;
          switch (result?.status) {
            case 'ACTIVE':
              this.state.set({ kind: 'active' });
              break;
            case 'PROVISIONING_PENDING':
              this.state.set({ kind: 'pending' });
              break;
            default:
              this.state.set({
                kind: 'unavailable',
                message: 'verificationUnavailable',
                retryable: false,
              });
          }
        },
        error: (error: unknown) => {
          const response: unknown = error instanceof HttpErrorResponse ? error.error : null;
          if (
            error instanceof HttpErrorResponse &&
            error.status === 400 &&
            response !== null &&
            typeof response === 'object' &&
            'code' in response &&
            response.code === 'INVALID_VERIFICATION'
          ) {
            this.token = null;
            this.state.set({ kind: 'invalid', message: 'verificationInvalid' });
            return;
          }
          const retryable =
            error instanceof HttpErrorResponse &&
            (error.status === 0 || error.status === 403 || error.status === 503);
          if (!retryable) this.token = null;
          this.state.set({
            kind: 'unavailable',
            message:
              error instanceof HttpErrorResponse && error.status === 403
                ? 'csrfError'
                : 'verificationUnavailable',
            retryable,
          });
        },
      });
  }
}
