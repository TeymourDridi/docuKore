import { TestBed } from '@angular/core/testing';
import { provideHttpClient, withXsrfConfiguration } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { AuthService } from './auth.service';

const doctor = {
  id: 'fictional-id',
  username: 'doctor',
  fullName: 'Fictional Doctor',
  email: null,
};

describe('AuthService', () => {
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
    document.cookie = 'XSRF-TOKEN=fictional-csrf; path=/';
  });
  afterEach(() => {
    http.verify();
    document.cookie = 'XSRF-TOKEN=; max-age=0; path=/';
  });

  it('restores the account from the server instead of browser storage', () => {
    auth.load().subscribe();
    http.expectOne('/api/auth/csrf').flush(null, { status: 204, statusText: 'No Content' });
    http.expectOne('/api/auth/me').flush(doctor);
    http.expectOne('/api/auth/setup').flush({ available: false });
    expect(auth.account()).toEqual(doctor);
    expect(auth.setupAvailable()).toBe(false);
  });

  it('uses Angular XSRF on login and refreshes CSRF before reading account', () => {
    auth.login('doctor', 'fictional-password').subscribe();
    const request = http.expectOne('/api/auth/login');
    expect(request.request.method).toBe('POST');
    expect(request.request.headers.get('X-XSRF-TOKEN')).toBe('fictional-csrf');
    expect(request.request.body.get('username')).toBe('doctor');
    request.flush({ authenticated: true });
    http.expectOne('/api/auth/csrf').flush(null, { status: 204, statusText: 'No Content' });
    http.expectOne('/api/auth/me').flush(doctor);
    expect(auth.account()?.username).toBe('doctor');
  });

  it('clears authenticated state and refreshes CSRF after logout', () => {
    auth.login('doctor', 'fictional-password').subscribe();
    http.expectOne('/api/auth/login').flush({ authenticated: true });
    http.expectOne('/api/auth/csrf').flush(null);
    http.expectOne('/api/auth/me').flush(doctor);
    auth.logout().subscribe();
    const logout = http.expectOne('/api/auth/logout');
    expect(logout.request.headers.get('X-XSRF-TOKEN')).toBe('fictional-csrf');
    logout.flush(null, { status: 204, statusText: 'No Content' });
    http.expectOne('/api/auth/csrf').flush(null);
    expect(auth.account()).toBeNull();
  });

  it('treats anonymous account responses as signed out but does not hide server failures', () => {
    auth.load().subscribe();
    http.expectOne('/api/auth/csrf').flush(null);
    http.expectOne('/api/auth/me').flush({}, { status: 401, statusText: 'Unauthorized' });
    http.expectOne('/api/auth/setup').flush({ available: true });
    expect(auth.account()).toBeNull();
    expect(auth.setupAvailable()).toBe(true);
    let failed = false;
    auth.load().subscribe({
      error: () => {
        failed = true;
      },
    });
    http.expectOne('/api/auth/csrf').flush(null);
    http.expectOne('/api/auth/setup').flush({ available: true });
    http.expectOne('/api/auth/me').flush({}, { status: 503, statusText: 'Unavailable' });
    expect(failed).toBe(true);
  });
});
