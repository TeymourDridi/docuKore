import { TestBed } from '@angular/core/testing';
import { provideHttpClient, withXsrfConfiguration } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { AuthService } from './auth.service';

const signup = {
  username: null,
  email: 'fictional.doctor@example.test',
  fullName: 'Fictional Doctor',
  cabinetName: 'Fictional Practice',
  password: 'fictional-passphrase',
  idempotencyKey: '00000000-0000-4000-8000-000000000001',
};
const pending = {
  registrationId: '00000000-0000-4000-8000-000000000002',
  cabinetId: null,
  status: 'PENDING_EMAIL_VERIFICATION',
};

describe('Registration API integration', () => {
  let auth: AuthService;
  let http: HttpTestingController;
  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(
          withXsrfConfiguration({ cookieName: 'XSRF-TOKEN', headerName: 'X-XSRF-TOKEN' })
        ),
        provideHttpClientTesting(),
      ],
    });
    auth = TestBed.inject(AuthService);
    http = TestBed.inject(HttpTestingController);
    document.cookie = 'XSRF-TOKEN=fictional-proof; path=/';
  });
  afterEach(() => {
    http.verify();
    document.cookie = 'XSRF-TOKEN=; max-age=0; path=/';
  });

  it('uses public server metadata to select registration fields', () => {
    let mode = '';
    auth.registrationSettings().subscribe((settings) => {
      mode = settings.deploymentMode;
    });
    http.expectNone('/api/auth/setup');
    http.expectOne('/api/auth/csrf').flush(null);
    http.expectOne('/api/auth/setup').flush({ available: false, deploymentMode: 'HOSTED' });
    expect(mode).toBe('HOSTED');
  });

  it('sends the exact signup body with CSRF and does not authenticate pending accounts', () => {
    let result: unknown;
    auth.signup(signup).subscribe((response) => {
      result = response;
    });
    http.expectNone('/api/auth/signup');
    http.expectOne('/api/auth/csrf').flush(null);
    const request = http.expectOne('/api/auth/signup');
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual(signup);
    expect(request.request.headers.get('X-XSRF-TOKEN')).toBe('fictional-proof');
    request.flush(pending, { status: 202, statusText: 'Accepted' });
    expect(result).toEqual(pending);
    expect(auth.account()).toBeNull();
    http.expectNone('/api/auth/me');
  });

  it('submits verification only to the backend and preserves provisioning-pending status', () => {
    let status = '';
    auth.verifyEmail('a'.repeat(43)).subscribe((response) => {
      status = response.status;
    });
    http.expectOne('/api/auth/csrf').flush(null);
    const request = http.expectOne('/api/auth/verify-email');
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual({ token: 'a'.repeat(43) });
    expect(request.request.headers.get('X-XSRF-TOKEN')).toBe('fictional-proof');
    request.flush(
      { ...pending, status: 'PROVISIONING_PENDING' },
      { status: 202, statusText: 'Accepted' }
    );
    expect(status).toBe('PROVISIONING_PENDING');
    expect(auth.account()).toBeNull();
  });

  it('requests resend without treating acceptance as proof of delivery', () => {
    let result: unknown;
    auth.resendVerification(signup.email).subscribe((response) => {
      result = response;
    });
    http.expectOne('/api/auth/csrf').flush(null);
    const request = http.expectOne('/api/auth/resend-verification');
    expect(request.request.body).toEqual({ email: signup.email });
    expect(request.request.method).toBe('POST');
    request.flush({ status: 'ACCEPTED' }, { status: 202, statusText: 'Accepted' });
    expect(result).toEqual({ status: 'ACCEPTED' });
  });

  it('preserves conflict errors and never automatically repeats signup', () => {
    let code = '';
    auth.signup(signup).subscribe({
      error: (error) => {
        code = error.error.code;
      },
    });
    http.expectOne('/api/auth/csrf').flush(null);
    http
      .expectOne('/api/auth/signup')
      .flush(
        { code: 'REGISTRATION_CONFLICT', message: 'Registration could not be accepted.' },
        { status: 409, statusText: 'Conflict' }
      );
    expect(code).toBe('REGISTRATION_CONFLICT');
    http.expectNone('/api/auth/signup');
  });
});
