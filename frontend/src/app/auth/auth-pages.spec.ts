import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { Login } from './login';
import { Setup } from './setup';

function fill(page: HTMLElement, name: string, value: string): void {
  const input = page.querySelector<HTMLInputElement>(`input[formControlName="${name}"]`);
  if (!input) throw new Error('Missing form input');
  input.value = value;
  input.dispatchEvent(new Event('input'));
}

describe('Authentication pages', () => {
  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
  });
  afterEach(() => TestBed.inject(HttpTestingController).verify());

  it('shows generic login failure, clears password and allows retry', async () => {
    const fixture = TestBed.createComponent(Login);
    await fixture.whenStable();
    const page = fixture.nativeElement as HTMLElement;
    fill(page, 'username', 'doctor');
    fill(page, 'password', 'fictional-wrong-password');
    page
      .querySelector('form')
      ?.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
    await fixture.whenStable();
    expect(page.querySelector('button[type="submit"]')?.hasAttribute('disabled')).toBe(true);
    TestBed.inject(HttpTestingController)
      .expectOne('/api/auth/login')
      .flush({}, { status: 401, statusText: 'Unauthorized' });
    await fixture.whenStable();
    expect(page.querySelector('[role="alert"]')?.textContent).toContain('Identifiants');
    expect(page.querySelector<HTMLInputElement>('input[type="password"]')?.value).toBe('');
    expect(page.querySelector('button[type="submit"]')?.hasAttribute('disabled')).toBe(false);
  });

  it('does not submit invalid setup and explains password requirements', async () => {
    const fixture = TestBed.createComponent(Setup);
    await fixture.whenStable();
    const page = fixture.nativeElement as HTMLElement;
    fill(page, 'username', 'doctor');
    fill(page, 'fullName', 'Fictional Doctor');
    fill(page, 'password', 'short');
    fill(page, 'confirmation', 'different');
    page
      .querySelector('form')
      ?.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
    await fixture.whenStable();
    expect(page.querySelector('[role="alert"]')).not.toBeNull();
    TestBed.inject(HttpTestingController).expectNone('/api/auth/setup');
  });
});
