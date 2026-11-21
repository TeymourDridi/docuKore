import { Location } from '@angular/common';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { vi } from 'vitest';
import { LanguageService } from '../i18n/language.service';
import { AuthService, RegistrationResult } from './auth.service';
import { VerifyEmail } from './verify-email';

@Component({ template: '' })
class Destination {}

const proof = `${'A'.repeat(41)}_-`;
const active: RegistrationResult = {
  registrationId: 'fictional-registration',
  cabinetId: 'fictional-cabinet',
  status: 'ACTIVE',
};

// Exercise the real component, router and AuthService against the HTTP test backend.
describe('Email verification page', () => {
  let harness: RouterTestingHarness;
  let http: HttpTestingController;
  let router: Router;

  beforeEach(async () => {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([
          { path: '', component: Destination },
          { path: 'verify-email', component: VerifyEmail },
          { path: 'login', component: Destination },
          { path: 'register', component: Destination },
        ]),
        provideHttpClient(),
        provideHttpClientTesting(),
      ],
    });
    http = TestBed.inject(HttpTestingController);
    router = TestBed.inject(Router);
    TestBed.inject(LanguageService).select('en');
    harness = await RouterTestingHarness.create();
  });

  afterEach(() => {
    http.verify();
    vi.restoreAllMocks();
  });

  function page(): HTMLElement {
    const element = harness.routeNativeElement;
    if (!element) throw new Error('Missing verification page');
    return element;
  }

  async function open(fragment: string | null = `token=${proof}`, query = ''): Promise<void> {
    await harness.navigateByUrl(
      `/verify-email${query}${fragment === null ? '' : `#${fragment}`}`,
      VerifyEmail
    );
    await harness.fixture.whenStable();
    harness.detectChanges();
  }

  function verificationRequest() {
    const csrf = http.expectOne('/api/auth/csrf');
    expect(csrf.request.method).toBe('GET');
    csrf.flush(null, { status: 204, statusText: 'No Content' });
    const verification = http.expectOne('/api/auth/verify-email');
    expect(verification.request.method).toBe('POST');
    expect(verification.request.body).toEqual({ token: proof });
    return verification;
  }

  function retryButton(): HTMLButtonElement {
    const button = page().querySelector<HTMLButtonElement>('button');
    if (!button) throw new Error('Missing verification retry button');
    return button;
  }

  it('consumes the fragment once, replaces its history entry and verifies automatically', async () => {
    const navigate = vi.spyOn(router, 'navigate');
    const replaceState = vi.spyOn(TestBed.inject(Location), 'replaceState');
    const pushState = vi.spyOn(TestBed.inject(Location), 'go');
    await open(`token=${proof}`, '?source=email');

    expect(navigate).toHaveBeenCalledWith(
      [],
      expect.objectContaining({
        preserveFragment: false,
        replaceUrl: true,
        queryParamsHandling: 'preserve',
      })
    );
    expect(router.url).toBe('/verify-email?source=email');
    expect(replaceState).toHaveBeenCalledWith('/verify-email?source=email', '', expect.any(Object));
    expect(pushState).toHaveBeenCalledTimes(1);
    expect(TestBed.inject(Location).path(true)).not.toContain(proof);
    expect(page().textContent).not.toContain(proof);
    const status = page().querySelector('[role="status"]');
    expect(status?.getAttribute('aria-live')).toBe('polite');
    expect(status?.getAttribute('aria-busy')).toBe('true');
    expect(status?.textContent).toContain(TestBed.inject(LanguageService).text('verifying'));

    const request = verificationRequest();
    await router.navigateByUrl('/verify-email?source=email');
    harness.detectChanges();
    http.expectNone('/api/auth/csrf');
    http.expectNone('/api/auth/verify-email');
    request.flush(active);
    harness.detectChanges();
  });

  it('does not send a request when the fragment is missing, even if a query token exists', async () => {
    await open(null, `?token=${proof}`);
    expect(page().textContent).toContain(
      TestBed.inject(LanguageService).text('verificationMissing')
    );
    expect(page().querySelector('[role="alert"]')?.getAttribute('aria-live')).toBe('assertive');
    expect(page().querySelector('a[href="/register"]')).not.toBeNull();
    expect(page().querySelector('button')).toBeNull();
    http.expectNone(() => true);
  });

  it.each([
    '',
    'token=',
    `token=${'A'.repeat(42)}`,
    `token=${'A'.repeat(44)}`,
    `token=${'A'.repeat(42)}%2B`,
    `token=${'A'.repeat(42)}%2F`,
    `token=${'A'.repeat(42)}%3D`,
    `token=${proof}%0A`,
    `token=${proof}%0D`,
    `token=${proof}&token=${'B'.repeat(43)}`,
  ])('does not request verification for a missing or malformed proof: %s', async (fragment) => {
    await open(fragment);
    expect(page().querySelector('[data-state="invalid"]')).not.toBeNull();
    expect(page().querySelector('a[href="/register"]')).not.toBeNull();
    expect(page().querySelector('button')).toBeNull();
    expect(router.url).toBe('/verify-email');
    http.expectNone(() => true);
  });

  it('shows ACTIVE verification success without signing in or activating a cabinet locally', async () => {
    await open();
    verificationRequest().flush(active);
    harness.detectChanges();
    const i18n = TestBed.inject(LanguageService);
    expect(page().textContent).toContain(i18n.text('verifiedTitle'));
    expect(page().textContent).toContain(i18n.text('verifiedIntro'));
    expect(page().querySelector('[role="status"]')?.getAttribute('aria-busy')).toBe('false');
    expect(page().querySelector('a[href="/login"]')?.textContent).toContain(
      i18n.text('continueToLogin')
    );
    expect(page().querySelector('button')).toBeNull();
    expect(TestBed.inject(AuthService).account()).toBeNull();
    expect(router.url).toBe('/verify-email');
    harness.detectChanges();
    http.expectNone(() => true);
  });

  it('distinguishes verified email from pending cabinet provisioning and offers login', async () => {
    await open();
    verificationRequest().flush({ ...active, cabinetId: null, status: 'PROVISIONING_PENDING' });
    harness.detectChanges();
    const i18n = TestBed.inject(LanguageService);
    expect(page().textContent).toContain(i18n.text('verificationPendingTitle'));
    expect(page().textContent).toContain(i18n.text('verificationPendingIntro'));
    expect(page().textContent).not.toContain(i18n.text('verifiedTitle'));
    expect(page().querySelector('a[href="/login"]')).not.toBeNull();
    expect(page().querySelector('button')).toBeNull();
    http.expectNone(() => true);
  });

  it('shows an invalid or expired link without replay or raw server messages', async () => {
    await open();
    verificationRequest().flush(
      { code: 'INVALID_VERIFICATION', message: 'Verification is invalid or expired.' },
      { status: 400, statusText: 'Bad Request' }
    );
    harness.detectChanges();
    expect(page().textContent).toContain(
      TestBed.inject(LanguageService).text('verificationInvalid')
    );
    expect(page().textContent).not.toContain('Verification is invalid or expired.');
    expect(page().querySelector('a[href="/register"]')).not.toBeNull();
    expect(page().querySelector('button')).toBeNull();
    await router.navigateByUrl('/verify-email');
    harness.detectChanges();
    http.expectNone(() => true);
  });

  it.each([0, 403, 503])(
    'only retries a transient %s failure after an explicit click',
    async (status) => {
      await open();
      const request = verificationRequest();
      if (status === 0) request.error(new ProgressEvent('error'));
      else request.flush({}, { status, statusText: status === 403 ? 'Forbidden' : 'Unavailable' });
      harness.detectChanges();
      expect(page().textContent).toContain(
        TestBed.inject(LanguageService).text(
          status === 403 ? 'csrfError' : 'verificationUnavailable'
        )
      );
      expect(router.url).toBe('/verify-email');
      harness.detectChanges();
      http.expectNone(() => true);
      const retry = retryButton();
      expect(retry.disabled).toBe(false);
      retry.click();
      // A second event can arrive before Angular rerenders the button: the busy guard must win.
      retry.click();
      harness.detectChanges();
      expect(page().querySelector('[role="status"]')?.getAttribute('aria-busy')).toBe('true');
      verificationRequest().flush(active);
      harness.detectChanges();
      expect(page().querySelector('button')).toBeNull();
      retry.click();
      harness.detectChanges();
      http.expectNone(() => true);
      expect(page().textContent).toContain(TestBed.inject(LanguageService).text('verifiedTitle'));
    }
  );

  it('can explicitly retry a CSRF bootstrap failure without sending the first proof POST', async () => {
    await open();
    http.expectOne('/api/auth/csrf').flush({}, { status: 503, statusText: 'Unavailable' });
    http.expectNone('/api/auth/verify-email');
    harness.detectChanges();
    retryButton().click();
    verificationRequest().flush(active);
    harness.detectChanges();
    expect(page().querySelector('a[href="/login"]')).not.toBeNull();
  });

  it('does not mistake an unrelated CSRF bootstrap 400 for an expired proof', async () => {
    await open();
    http.expectOne('/api/auth/csrf').flush({}, { status: 400, statusText: 'Bad Request' });
    harness.detectChanges();
    const i18n = TestBed.inject(LanguageService);
    expect(page().textContent).toContain(i18n.text('verificationUnavailable'));
    expect(page().textContent).not.toContain(i18n.text('verificationInvalid'));
    expect(page().querySelector('button')).toBeNull();
    http.expectNone('/api/auth/verify-email');
  });

  it('does not offer retry for a non-transient rejection', async () => {
    await open();
    verificationRequest().flush({}, { status: 422, statusText: 'Unprocessable Entity' });
    harness.detectChanges();
    expect(page().textContent).toContain(
      TestBed.inject(LanguageService).text('verificationUnavailable')
    );
    expect(page().querySelector('button')).toBeNull();
    http.expectNone(() => true);
  });

  it.each(['PENDING_EMAIL_VERIFICATION', 'DELIVERY_FAILED'])(
    'never labels an unexpected successful %s response as verified or retries it',
    async (status) => {
      await open();
      verificationRequest().flush({ ...active, status });
      harness.detectChanges();
      const i18n = TestBed.inject(LanguageService);
      expect(page().textContent).toContain(i18n.text('verificationUnavailable'));
      expect(page().textContent).not.toContain(i18n.text('verifiedTitle'));
      expect(page().textContent).not.toContain(i18n.text('verificationPendingTitle'));
      expect(page().querySelector('button')).toBeNull();
      expect(page().querySelector('a[href="/register"]')).not.toBeNull();
      http.expectNone(() => true);
    }
  );

  it('remains public when an account is already authenticated', async () => {
    TestBed.inject(AuthService).login('fictional-doctor', 'fictional-password').subscribe();
    http.expectOne('/api/auth/login').flush({ authenticated: true });
    http.expectOne('/api/auth/csrf').flush(null);
    http.expectOne('/api/auth/me').flush({
      id: 'fictional-account',
      username: 'fictional-doctor',
      fullName: 'Fictional Doctor',
      email: 'fictional@example.test',
    });
    await open();
    verificationRequest().flush(active);
    harness.detectChanges();
    expect(router.url).toBe('/verify-email');
    expect(TestBed.inject(AuthService).account()?.id).toBe('fictional-account');
    expect(page().querySelector('a[href="/login"]')).not.toBeNull();
    http.expectNone(() => true);
  });

  it('cancels verification when the page is destroyed', async () => {
    await open();
    const request = verificationRequest();
    await router.navigateByUrl('/login');
    expect(request.cancelled).toBe(true);
    http.expectNone(() => true);
  });
});
