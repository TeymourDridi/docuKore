import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { Register } from './register';
import { LanguageService } from '../i18n/language.service';
import { vi } from 'vitest';

const signupUrl = '/api/auth/signup';
const resendUrl = '/api/auth/resend-verification';
const fictionalPassword = 'fictional-passphrase';

describe('Doctor registration', () => {
  let http: HttpTestingController;
  let fixture: ComponentFixture<Register>;
  let page: HTMLElement;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    TestBed.inject(LanguageService).select('en');
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    http.verify();
    vi.restoreAllMocks();
  });

  async function start(mode: 'LOCAL' | 'HOSTED' = 'HOSTED'): Promise<void> {
    fixture = TestBed.createComponent(Register);
    page = fixture.nativeElement as HTMLElement;
    await fixture.whenStable();
    http.expectOne('/api/auth/csrf').flush({});
    http.expectOne('/api/auth/setup').flush({ available: false, deploymentMode: mode });
    await fixture.whenStable();
  }

  function fill(name: string, value: string): void {
    const input = page.querySelector<HTMLInputElement>(`input[formControlName="${name}"]`);
    if (!input) throw new Error(`Missing registration input: ${name}`);
    input.value = value;
    input.dispatchEvent(new Event('input'));
    input.dispatchEvent(new Event('blur'));
  }

  function validForm(mode: 'LOCAL' | 'HOSTED' = 'HOSTED'): void {
    fill('fullName', 'Fictional Doctor');
    fill('cabinetName', 'Fictional Practice');
    if (mode === 'LOCAL') fill('username', 'doctor.local');
    else fill('email', 'doctor@example.test');
    fill('password', fictionalPassword);
    fill('confirmation', fictionalPassword);
  }

  async function submit(): Promise<void> {
    page
      .querySelector('form')
      ?.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
    await fixture.whenStable();
  }

  function signupRequest() {
    http.expectOne('/api/auth/csrf').flush({});
    return http.expectOne(signupUrl);
  }

  it('loads backend metadata before exposing the form, and does not infer mode from setup availability', async () => {
    fixture = TestBed.createComponent(Register);
    page = fixture.nativeElement as HTMLElement;
    await fixture.whenStable();
    expect(page.querySelector('form')).toBeNull();
    expect(page.querySelector('[role="status"]')?.textContent).toContain('registration');
    http.expectOne('/api/auth/csrf').flush({});
    http.expectOne('/api/auth/setup').flush({ available: true, deploymentMode: 'HOSTED' });
    await fixture.whenStable();
    expect(page.querySelector('input[formControlName="username"]')).toBeNull();
    expect(page.querySelector<HTMLInputElement>('input[formControlName="email"]')?.required).toBe(
      true
    );
  });

  it('requires a local username but permits an absent email and submits normalized names', async () => {
    await start('LOCAL');
    validForm('LOCAL');
    fill('username', 'not valid');
    await submit();
    http.expectNone(signupUrl);
    http.expectNone('/api/auth/csrf');
    expect(page.querySelector('#registration-username-error')).not.toBeNull();
    fill('username', 'Doctor.LOCAL');
    fill('fullName', '  José Fictional  ');
    fill('cabinetName', '  Cabinet Fictional  ');
    await submit();
    const request = signupRequest();
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toMatchObject({
      username: 'Doctor.LOCAL',
      email: null,
      fullName: 'José Fictional',
      cabinetName: 'Cabinet Fictional',
      password: fictionalPassword,
    });
    expect(request.request.body.idempotencyKey).toMatch(
      /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i
    );
    request.flush({
      registrationId: 'fictional-registration',
      cabinetId: 'fictional-cabinet',
      status: 'ACTIVE',
    });
    await fixture.whenStable();
    expect(page.textContent).toContain('Your account and practice are ready');
    expect(
      page
        .querySelector<HTMLAnchorElement>('[data-testid="registration-login"]')
        ?.getAttribute('href')
    ).toBe('/login');
  });

  it('requires hosted email and never asks for or sends a duplicate username', async () => {
    await start();
    validForm();
    fill('email', '');
    await submit();
    http.expectNone('/api/auth/csrf');
    expect(page.querySelector('#registration-email-error')).not.toBeNull();
    fill('email', ' ');
    await submit();
    http.expectNone('/api/auth/csrf');
    expect(page.querySelector('#registration-email-error')).not.toBeNull();
    fill('email', 'invalid-email');
    await submit();
    http.expectNone(signupUrl);
    fill('email', 'doctor@example.test');
    await submit();
    const request = signupRequest();
    expect(request.request.body.username).toBeNull();
    expect(request.request.body.email).toBe('doctor@example.test');
    request.flush(
      {
        registrationId: 'fictional-registration',
        cabinetId: null,
        status: 'PENDING_EMAIL_VERIFICATION',
      },
      { status: 202, statusText: 'Accepted' }
    );
    await fixture.whenStable();
    expect(page.textContent).toContain('Check your inbox');
    expect(page.querySelector('form')).toBeNull();
    expect(page.querySelector('input[type="password"]')).toBeNull();
  });

  it('accepts the backend email length boundary and normalizes email without persisting credentials or keys', async () => {
    await start();
    validForm();
    const store = vi.spyOn(Storage.prototype, 'setItem');
    const email = `${'a'.repeat(242)}@EXAMPLE.TEST`;
    expect(email.length).toBe(255);
    fill('email', `  ${email}  `);
    await submit();
    const request = signupRequest();
    expect(request.request.body.email).toBe(email.toLowerCase());
    expect(store).not.toHaveBeenCalled();
    request.flush({
      registrationId: 'fictional-registration',
      cabinetId: null,
      status: 'PENDING_EMAIL_VERIFICATION',
    });
    await fixture.whenStable();
    expect(store).not.toHaveBeenCalled();
    store.mockRestore();
  });

  it('validates names, email length, password code points and UTF-8 bytes, and confirmation accessibly', async () => {
    await start();
    validForm();
    fill('fullName', '   ');
    fill('cabinetName', 'Fictional\u0007Practice');
    fill('password', 'short');
    fill('confirmation', 'different');
    await submit();
    expect(page.querySelector('#registration-name-error')).not.toBeNull();
    expect(page.querySelector('#registration-cabinet-error')).not.toBeNull();
    expect(page.querySelector('#registration-password-error')).not.toBeNull();
    expect(page.querySelector('#registration-confirmation-error')?.textContent).toContain('match');
    expect(
      page.querySelector('input[formControlName="password"]')?.getAttribute('aria-invalid')
    ).toBe('true');
    http.expectNone('/api/auth/csrf');
    fill('fullName', 'a'.repeat(256));
    fill('cabinetName', 'Fictional Practice');
    fill('email', `${'a'.repeat(64)}@${'b'.repeat(63)}.${'c'.repeat(63)}.${'d'.repeat(63)}`);
    fill('password', 'é'.repeat(37));
    fill('confirmation', 'é'.repeat(37));
    await submit();
    expect(page.querySelector('#registration-name-error')).not.toBeNull();
    expect(page.querySelector('#registration-email-error')).not.toBeNull();
    expect(page.querySelector('#registration-password-error')).not.toBeNull();
    http.expectNone('/api/auth/csrf');
    fill('fullName', 'Fictional Doctor');
    fill('email', 'doctor@example.test');
    fill('password', '😀'.repeat(11));
    fill('confirmation', '😀'.repeat(11));
    await submit();
    http.expectNone('/api/auth/csrf');
    fill('password', '😀'.repeat(12));
    fill('confirmation', '😀'.repeat(12));
    await submit();
    signupRequest().flush(
      {
        registrationId: 'fictional-registration',
        cabinetId: null,
        status: 'PENDING_EMAIL_VERIFICATION',
      },
      { status: 202, statusText: 'Accepted' }
    );
  });

  it('locks all fields and prevents duplicate requests during CSRF and signup', async () => {
    await start();
    validForm();
    await submit();
    expect(page.querySelector<HTMLFieldSetElement>('fieldset')?.disabled).toBe(true);
    expect(page.querySelector<HTMLButtonElement>('button[type="submit"]')?.disabled).toBe(true);
    await submit();
    const request = signupRequest();
    await submit();
    http.expectNone('/api/auth/csrf');
    http.expectNone(signupUrl);
    request.flush({}, { status: 503, statusText: 'Service Unavailable' });
    await fixture.whenStable();
    expect(page.querySelector<HTMLFieldSetElement>('fieldset')?.disabled).toBe(false);
    expect(page.querySelector<HTMLInputElement>('input[formControlName="password"]')?.value).toBe(
      fictionalPassword
    );
  });

  it('reuses the idempotency key for unmodified retries and rotates it for submitted changes including password', async () => {
    await start();
    validForm();
    await submit();
    const first = signupRequest();
    const originalKey: string = first.request.body.idempotencyKey;
    first.flush({}, { status: 503, statusText: 'Service Unavailable' });
    await fixture.whenStable();
    await submit();
    const retry = signupRequest();
    expect(retry.request.body.idempotencyKey).toBe(originalKey);
    retry.error(new ProgressEvent('error'));
    await fixture.whenStable();
    fill('cabinetName', 'Another Fictional Practice');
    await submit();
    const changed = signupRequest();
    const changedKey: string = changed.request.body.idempotencyKey;
    expect(changedKey).not.toBe(originalKey);
    changed.flush({}, { status: 503, statusText: 'Service Unavailable' });
    await fixture.whenStable();
    fill('password', 'different-fictional-passphrase');
    fill('confirmation', 'different-fictional-passphrase');
    await submit();
    const changedPassword = signupRequest();
    expect(changedPassword.request.body.idempotencyKey).not.toBe(changedKey);
    changedPassword.flush({
      registrationId: 'fictional-registration',
      cabinetId: null,
      status: 'PENDING_EMAIL_VERIFICATION',
    });
  });

  it('reports delivery failure truthfully and handles generic resend acceptance without promising delivery', async () => {
    await start();
    validForm();
    await submit();
    signupRequest().flush(
      { registrationId: 'fictional-registration', cabinetId: null, status: 'DELIVERY_FAILED' },
      { status: 202, statusText: 'Accepted' }
    );
    await fixture.whenStable();
    expect(page.textContent).toContain('was not sent');
    page.querySelector<HTMLButtonElement>('[data-testid="resend-verification"]')?.click();
    await fixture.whenStable();
    page.querySelector<HTMLButtonElement>('[data-testid="resend-verification"]')?.click();
    http.expectOne('/api/auth/csrf').flush({});
    const resend = http.expectOne(resendUrl);
    expect(resend.request.body).toEqual({ email: 'doctor@example.test' });
    resend.flush({ status: 'ACCEPTED' }, { status: 202, statusText: 'Accepted' });
    await fixture.whenStable();
    expect(page.textContent).toContain('does not confirm email delivery');
    http.expectNone(resendUrl);
  });

  it('permits a resend retry after failure and offers resend for pending verification', async () => {
    await start();
    validForm();
    await submit();
    signupRequest().flush({
      registrationId: 'fictional-registration',
      cabinetId: null,
      status: 'PENDING_EMAIL_VERIFICATION',
    });
    await fixture.whenStable();
    page.querySelector<HTMLButtonElement>('[data-testid="resend-verification"]')?.click();
    await fixture.whenStable();
    http.expectOne('/api/auth/csrf').flush({});
    http.expectOne(resendUrl).error(new ProgressEvent('error'));
    await fixture.whenStable();
    expect(page.querySelector('[role="alert"]')?.textContent).toContain('connection');
    page.querySelector<HTMLButtonElement>('[data-testid="resend-verification"]')?.click();
    await fixture.whenStable();
    http.expectOne('/api/auth/csrf').flush({});
    http.expectOne(resendUrl).flush({ status: 'ACCEPTED' });
  });

  it('does not claim the practice is active when provisioning is pending', async () => {
    await start('LOCAL');
    validForm('LOCAL');
    await submit();
    signupRequest().flush(
      {
        registrationId: 'fictional-registration',
        cabinetId: 'fictional-cabinet',
        status: 'PROVISIONING_PENDING',
      },
      { status: 202, statusText: 'Accepted' }
    );
    await fixture.whenStable();
    expect(page.textContent).toContain('your practice is not ready yet');
    expect(page.textContent).not.toContain('Your account and practice are ready');
    expect(page.querySelector('[data-testid="registration-login"]')?.getAttribute('href')).toBe(
      '/login'
    );
    expect(page.querySelector('[data-testid="resend-verification"]')).toBeNull();
  });

  it.each([
    { status: 400, message: 'Check the fields' },
    { status: 403, message: 'security session' },
    { status: 409, message: 'existing account' },
    { status: 503, message: 'unavailable' },
  ])(
    'keeps credentials and allows deliberate retry after HTTP $status',
    async ({ status, message }) => {
      await start();
      validForm();
      await submit();
      signupRequest().flush(
        { code: 'FICTIONAL_ERROR', message: 'Internal detail must not be displayed' },
        { status, statusText: 'Failure' }
      );
      await fixture.whenStable();
      expect(page.querySelector('[role="alert"]')?.textContent).toContain(message);
      expect(page.textContent).not.toContain('Internal detail');
      expect(page.querySelector<HTMLInputElement>('input[formControlName="password"]')?.value).toBe(
        fictionalPassword
      );
      expect(page.querySelector<HTMLButtonElement>('button[type="submit"]')?.disabled).toBe(false);
      if (status === 409)
        expect(
          page.querySelector('[data-testid="existing-account-login"]')?.getAttribute('href')
        ).toBe('/login');
      http.expectNone('/api/auth/csrf');
      http.expectNone(signupUrl);
    }
  );

  it('offers a settings retry after failure without selecting a default mode', async () => {
    fixture = TestBed.createComponent(Register);
    page = fixture.nativeElement as HTMLElement;
    await fixture.whenStable();
    http.expectOne('/api/auth/csrf').flush({});
    http.expectOne('/api/auth/setup').error(new ProgressEvent('error'));
    await fixture.whenStable();
    expect(page.querySelector('form')).toBeNull();
    expect(page.querySelector('[role="alert"]')).not.toBeNull();
    page.querySelector<HTMLButtonElement>('[data-testid="settings-retry"]')?.click();
    await fixture.whenStable();
    http.expectOne('/api/auth/csrf').flush({});
    http.expectOne('/api/auth/setup').flush({ available: false, deploymentMode: 'LOCAL' });
    await fixture.whenStable();
    expect(page.querySelector('input[formControlName="username"]')).not.toBeNull();
  });

  it('keeps passwords hidden by default and toggles both fields independently without submitting', async () => {
    await start();
    expect(page.querySelector<HTMLInputElement>('#registration-password')?.type).toBe('password');
    expect(page.querySelector<HTMLInputElement>('#registration-confirmation')?.type).toBe(
      'password'
    );
    page.querySelector<HTMLButtonElement>('[data-testid="toggle-password"]')?.click();
    await fixture.whenStable();
    expect(page.querySelector<HTMLInputElement>('#registration-password')?.type).toBe('text');
    expect(page.querySelector<HTMLInputElement>('#registration-confirmation')?.type).toBe(
      'password'
    );
    expect(
      page.querySelector('[data-testid="toggle-password"]')?.getAttribute('aria-pressed')
    ).toBe('true');
    page.querySelector<HTMLButtonElement>('[data-testid="toggle-confirmation"]')?.click();
    await fixture.whenStable();
    expect(page.querySelector<HTMLInputElement>('#registration-confirmation')?.type).toBe('text');
    http.expectNone('/api/auth/csrf');
    http.expectNone(signupUrl);
  });
});
