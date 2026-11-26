import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter, Router } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { routes } from './app.routes';
import { Account } from './auth/auth.service';

const doctor: Account = {
  id: '10000000-0000-4000-8000-000000000001',
  username: 'fictional.doctor',
  fullName: 'Fictional Doctor',
  email: null,
};

describe('Application shell routes', () => {
  let http: HttpTestingController;
  let router: Router;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideRouter(routes), provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    router = TestBed.inject(Router);
  });

  afterEach(() => http.verify());

  function fill(page: HTMLElement, name: string, value: string): void {
    const input = page.querySelector<HTMLInputElement>(`input[formControlName="${name}"]`)!;
    input.value = value;
    input.dispatchEvent(new Event('input'));
  }

  async function respond(account: Account | null, available: boolean): Promise<void> {
    const csrf = await vi.waitFor(() => http.expectOne('/api/auth/csrf'));
    csrf.flush(null);
    const me = http.expectOne('/api/auth/me');
    if (account) me.flush(account);
    else me.flush({}, { status: 401, statusText: 'Unauthorized' });
    http.expectOne('/api/auth/setup').flush({ available, deploymentMode: 'LOCAL' });
    await Promise.resolve();
  }

  function expectPublicPage(harness: RouterTestingHarness, selector: string): void {
    const page = harness.routeNativeElement;
    expect(page?.matches('app-public-shell')).toBe(true);
    expect(page?.querySelector(selector)).not.toBeNull();
    expect(page?.querySelector('app-workspace-shell, app-workspace-prototype')).toBeNull();
  }

  function expectPatientWorkspace(harness: RouterTestingHarness): void {
    const page = harness.routeNativeElement;
    expect(router.url).toBe('/workspace/patients');
    expect(page?.matches('app-workspace-shell')).toBe(true);
    expect(page?.querySelector('app-patient-list')).not.toBeNull();
    expect(
      page?.querySelector('app-public-shell, app-workspace, app-workspace-prototype')
    ).toBeNull();
    http
      .expectOne((request) => request.url === '/api/patients')
      .flush({ content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 });
  }

  for (const { url, available, selector } of [
    { url: '/login', available: false, selector: 'app-login' },
    { url: '/setup', available: true, selector: 'app-setup' },
  ]) {
    it(`wraps signed-out ${url} in the public shell`, async () => {
      const harness = await RouterTestingHarness.create();
      const navigation = harness.navigateByUrl(url);
      await respond(null, available);
      await navigation;
      expect(router.url).toBe(url);
      expectPublicPage(harness, selector);
    });
  }

  it('wraps registration in the public shell without an account or first-run guard', async () => {
    const harness = await RouterTestingHarness.create();
    await harness.navigateByUrl('/register');
    http.expectOne('/api/auth/csrf').flush(null);
    http.expectOne('/api/auth/setup').flush({ available: true, deploymentMode: 'LOCAL' });
    await harness.fixture.whenStable();
    expect(router.url).toBe('/register');
    expectPublicPage(harness, 'app-register');
    expect(
      harness.routeNativeElement?.querySelector('input[formControlName="username"]')
    ).not.toBeNull();
    http.expectNone('/api/auth/me');
  });

  it('wraps email verification in the public shell without requiring a session', async () => {
    const harness = await RouterTestingHarness.create();
    await harness.navigateByUrl('/verify-email');
    expect(router.url).toBe('/verify-email');
    expectPublicPage(harness, 'app-verify-email');
    expect(harness.routeNativeElement?.querySelector('[role="alert"]')).not.toBeNull();
    http.expectNone('/api/auth/me');
    http.expectNone('/api/auth/setup');
  });

  for (const { url, available, destination, selector } of [
    { url: '/login', available: true, destination: '/setup', selector: 'app-setup' },
    { url: '/setup', available: false, destination: '/login', selector: 'app-login' },
  ]) {
    it(`preserves the signed-out guard redirect from ${url} to ${destination}`, async () => {
      const harness = await RouterTestingHarness.create();
      const navigation = harness.navigateByUrl(url);
      await respond(null, available);
      await respond(null, available);
      await navigation;
      expect(router.url).toBe(destination);
      expectPublicPage(harness, selector);
    });
  }

  for (const url of ['/login', '/setup']) {
    it(`redirects authenticated ${url} access to the patient workspace`, async () => {
      const harness = await RouterTestingHarness.create();
      const navigation = harness.navigateByUrl(url);
      await respond(doctor, false);
      await respond(doctor, false);
      await navigation;
      expectPatientWorkspace(harness);
    });
  }

  for (const { account, available, destination, selector } of [
    { account: null, available: true, destination: '/setup', selector: 'app-setup' },
    { account: null, available: false, destination: '/login', selector: 'app-login' },
    { account: doctor, available: false, destination: '/workspace/patients', selector: null },
  ]) {
    it(`preserves the entry redirect to ${destination}`, async () => {
      const harness = await RouterTestingHarness.create();
      await harness.navigateByUrl('/');
      await respond(account, available);
      await respond(account, available);
      await harness.fixture.whenStable();
      harness.detectChanges();
      expect(router.url).toBe(destination);
      if (selector) expectPublicPage(harness, selector);
      else expectPatientWorkspace(harness);
    });
  }

  it('redirects authorized workspace entry to the real patient-list child without the prototype', async () => {
    const harness = await RouterTestingHarness.create();
    const navigation = harness.navigateByUrl('/workspace');
    await respond(doctor, false);
    await navigation;
    expectPatientWorkspace(harness);
  });

  it('submits the existing login page and reaches the patient workspace', async () => {
    const harness = await RouterTestingHarness.create();
    const navigation = harness.navigateByUrl('/login');
    await respond(null, false);
    await navigation;
    const page = harness.routeNativeElement!;
    fill(page, 'username', doctor.username);
    fill(page, 'password', 'fictional-valid-password');
    page
      .querySelector('form')!
      .dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
    http.expectOne('/api/auth/login').flush({ authenticated: true });
    http.expectOne('/api/auth/csrf').flush(null);
    http.expectOne('/api/auth/me').flush(doctor);
    await respond(doctor, false);
    await harness.fixture.whenStable();
    harness.detectChanges();
    expectPatientWorkspace(harness);
  });

  it('submits the existing setup page and returns to public login with the created notice', async () => {
    const harness = await RouterTestingHarness.create();
    const navigation = harness.navigateByUrl('/setup');
    await respond(null, true);
    await navigation;
    const page = harness.routeNativeElement!;
    fill(page, 'username', doctor.username);
    fill(page, 'fullName', doctor.fullName!);
    fill(page, 'password', 'fictional-valid-password');
    fill(page, 'confirmation', 'fictional-valid-password');
    page
      .querySelector('form')!
      .dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
    const setup = http.expectOne('/api/auth/setup');
    expect(setup.request.method).toBe('POST');
    setup.flush(null);
    await respond(null, false);
    await harness.fixture.whenStable();
    harness.detectChanges();
    expect(router.url).toBe('/login?created=1');
    expectPublicPage(harness, 'app-login');
    expect(harness.routeNativeElement?.querySelector('.notice')).not.toBeNull();
  });

  it('opens an authorized patient deep link with an accessible active navigation item', async () => {
    const harness = await RouterTestingHarness.create();
    const navigation = harness.navigateByUrl('/workspace/patients');
    await respond(doctor, false);
    await navigation;
    expectPatientWorkspace(harness);
    expect(harness.routeNativeElement?.querySelector('nav a')?.getAttribute('aria-current')).toBe(
      'page'
    );
  });

  it('redirects unauthorized direct patient-list access to expired-session login', async () => {
    const harness = await RouterTestingHarness.create();
    const navigation = harness.navigateByUrl('/workspace/patients');
    await respond(null, false);
    await respond(null, false);
    await navigation;
    expect(router.url).toBe('/login?expired=1');
    expectPublicPage(harness, 'app-login');
    expect(harness.routeNativeElement?.querySelector('app-patient-list')).toBeNull();
    http.expectNone((request) => request.url.startsWith('/api/patients'));
  });
});
