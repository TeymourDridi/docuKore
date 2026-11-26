import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { routes } from '../app.routes';
import { PublicShell } from '../layout/public-shell/public-shell';

describe('Public registration routes', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideRouter(routes), provideHttpClient(), provideHttpClientTesting()],
    });
  });
  afterEach(() => TestBed.inject(HttpTestingController).verify());

  it('opens registration without bootstrap/setup redirection', async () => {
    const harness = await RouterTestingHarness.create();
    await harness.navigateByUrl('/register', PublicShell);
    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/auth/csrf').flush(null);
    http.expectOne('/api/auth/setup').flush({ available: true, deploymentMode: 'LOCAL' });
    await harness.fixture.whenStable();
    expect(
      harness.routeNativeElement?.querySelector('input[formControlName="username"]')
    ).not.toBeNull();
    http.expectNone('/api/auth/me');
  });

  it('opens verification without a sign-out or first-run guard', async () => {
    const harness = await RouterTestingHarness.create();
    await harness.navigateByUrl('/verify-email', PublicShell);
    expect(harness.routeNativeElement?.querySelector('[role="alert"]')).not.toBeNull();
    TestBed.inject(HttpTestingController).expectNone('/api/auth/me');
    TestBed.inject(HttpTestingController).expectNone('/api/auth/setup');
  });
});
