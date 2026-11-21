import { TestBed } from '@angular/core/testing';
import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter, Router } from '@angular/router';
import { AuthService } from './auth.service';
import { sessionInterceptor } from './session.interceptor';
import { accountGuard, signedOutGuard } from './auth.guards';
import { Component } from '@angular/core';

@Component({ template: '' })
class Page {}
const doctor = {
  id: 'fictional-id',
  username: 'doctor',
  fullName: 'Fictional Doctor',
  email: null,
};

describe('Account routing and session expiry', () => {
  let http: HttpTestingController;
  let router: Router;
  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([
          { path: '', component: Page },
          { path: 'setup', component: Page, canActivate: [signedOutGuard] },
          { path: 'login', component: Page, canActivate: [signedOutGuard] },
          { path: 'workspace', component: Page, canActivate: [accountGuard] },
        ]),
        provideHttpClient(withInterceptors([sessionInterceptor])),
        provideHttpClientTesting(),
      ],
    });
    http = TestBed.inject(HttpTestingController);
    router = TestBed.inject(Router);
  });
  afterEach(() => http.verify());

  async function respond(account: typeof doctor | null, available: boolean): Promise<void> {
    await new Promise<void>((resolve) => setTimeout(resolve, 0));
    http.expectOne('/api/auth/csrf').flush(null);
    const me = http.expectOne('/api/auth/me');
    if (account) me.flush(account);
    else me.flush({}, { status: 401, statusText: 'Unauthorized' });
    http.expectOne('/api/auth/setup').flush({ available });
    await Promise.resolve();
  }

  it('redirects first-run login to setup and authenticated setup to the shell', async () => {
    const login = router.navigateByUrl('/login');
    await respond(null, true);
    await respond(null, true);
    await login;
    expect(router.url).toBe('/setup');
    await router.navigateByUrl('/');
    const setup = router.navigateByUrl('/setup');
    await respond(doctor, false);
    await respond(doctor, false);
    await setup;
    expect(router.url).toBe('/workspace');
  });

  it('clears stale account state and returns to login when the server session expires', async () => {
    const auth = TestBed.inject(AuthService);
    auth.load().subscribe();
    await respond(doctor, false);
    let failed = false;
    TestBed.inject(HttpClient)
      .get('/api/private')
      .subscribe({
        error: () => {
          failed = true;
        },
      });
    http.expectOne('/api/private').flush({}, { status: 401, statusText: 'Unauthorized' });
    expect(auth.account()).toBeNull();
    expect(failed).toBe(true);
    await respond(null, false);
    await new Promise<void>((resolve) => setTimeout(resolve, 0));
    expect(router.url).toContain('/login?expired=1');
  });
});
