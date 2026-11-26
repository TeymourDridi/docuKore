import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { AuthService } from '../../auth/auth.service';
import { LanguageService } from '../../i18n/language.service';
import { WorkspaceShell } from './workspace-shell';

describe('WorkspaceShell', () => {
  let http: HttpTestingController;
  let auth: AuthService;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [WorkspaceShell],
      providers: [
        provideRouter([{ path: 'login', children: [] }]),
        provideHttpClient(),
        provideHttpClientTesting(),
      ],
    });
    http = TestBed.inject(HttpTestingController);
    auth = TestBed.inject(AuthService);
    TestBed.inject(LanguageService).select('en');
  });

  afterEach(() => {
    http.verify();
    TestBed.inject(LanguageService).select('fr');
  });

  function restore(fullName: string | null = 'Fictional Doctor'): void {
    auth.load().subscribe();
    http.expectOne('/api/auth/csrf').flush(null);
    http.expectOne('/api/auth/me').flush({
      id: 'fictional-account',
      username: 'fictional.doctor',
      fullName,
      email: null,
    });
    http.expectOne('/api/auth/setup').flush({ available: false, deploymentMode: 'LOCAL' });
  }

  it('shows the restored account and only the real patient navigation link', async () => {
    restore();
    const fixture = TestBed.createComponent(WorkspaceShell);
    await fixture.whenStable();
    const element: HTMLElement = fixture.nativeElement;
    expect(element.querySelector('header')?.textContent).toContain('Fictional Doctor');
    const links = element.querySelectorAll('nav a');
    expect(links.length).toBe(1);
    expect(links[0].getAttribute('href')).toBe('/workspace/patients');
    expect(links[0].textContent).toContain('Patients');
    expect(element.querySelector('main router-outlet')).not.toBeNull();
    expect(element.querySelector('app-workspace-prototype')).toBeNull();
    http.expectNone((request) => request.url.startsWith('/api/patients'));
  });

  it('uses the actual username when the account has no full name', async () => {
    restore(null);
    const fixture = TestBed.createComponent(WorkspaceShell);
    await fixture.whenStable();
    expect(fixture.nativeElement.querySelector('header').textContent).toContain('fictional.doctor');
  });

  it('changes workspace labels and document direction without losing the account', async () => {
    restore();
    const fixture = TestBed.createComponent(WorkspaceShell);
    await fixture.whenStable();
    const element: HTMLElement = fixture.nativeElement;
    const select = element.querySelector('select')!;
    select.value = 'ar';
    select.dispatchEvent(new Event('change'));
    await fixture.whenStable();
    expect(document.documentElement.dir).toBe('rtl');
    expect(document.documentElement.lang).toBe('ar');
    expect(select.getAttribute('aria-label')).toBe('اللغة');
    expect(element.querySelector('nav a')?.textContent).toContain('المرضى');
    expect(element.querySelector('button.logout')?.textContent).toContain('تسجيل الخروج');
    expect(element.textContent).toContain('Fictional Doctor');
    select.value = 'en';
    select.dispatchEvent(new Event('change'));
    await fixture.whenStable();
    expect(document.documentElement.dir).toBe('ltr');
    expect(element.querySelector('nav a')?.textContent).toContain('Patients');
  });

  it('disables duplicate logout, clears the account and navigates to login after CSRF refresh', async () => {
    restore();
    const fixture = TestBed.createComponent(WorkspaceShell);
    await fixture.whenStable();
    const button: HTMLButtonElement = fixture.nativeElement.querySelector('button.logout');
    button.click();
    fixture.detectChanges();
    expect(button.disabled).toBe(true);
    expect(button.textContent).toContain('Signing out');
    button.click();
    const logout = http.expectOne('/api/auth/logout');
    expect(logout.request.method).toBe('POST');
    logout.flush(null);
    http.expectOne('/api/auth/csrf').flush(null);
    await fixture.whenStable();
    expect(auth.account()).toBeNull();
    expect(TestBed.inject(Router).url).toBe('/login');
    expect(button.disabled).toBe(false);
  });

  it('shows a safe logout error and permits a deliberate retry', async () => {
    restore();
    const fixture = TestBed.createComponent(WorkspaceShell);
    await fixture.whenStable();
    const element: HTMLElement = fixture.nativeElement;
    const button = element.querySelector<HTMLButtonElement>('button.logout')!;
    button.click();
    http
      .expectOne('/api/auth/logout')
      .flush({ privateDetail: 'must-not-be-visible' }, { status: 503, statusText: 'Unavailable' });
    await fixture.whenStable();
    expect(button.disabled).toBe(false);
    expect(element.querySelector('[role="alert"]')?.textContent).toContain('unavailable');
    expect(element.textContent).not.toContain('must-not-be-visible');
    expect(auth.account()?.username).toBe('fictional.doctor');
    expect(TestBed.inject(Router).url).toBe('/');
    button.click();
    fixture.detectChanges();
    expect(element.querySelector('[role="alert"]')).toBeNull();
    http.expectOne('/api/auth/logout').flush(null);
    http.expectOne('/api/auth/csrf').flush(null);
    await fixture.whenStable();
    expect(TestBed.inject(Router).url).toBe('/login');
  });
});
