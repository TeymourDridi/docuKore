import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { App } from './app';

describe('App', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [App],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();
  });

  it('renders the Docukore identity and an accessible language selector', async () => {
    const fixture = TestBed.createComponent(App);
    await fixture.whenStable();
    const page = fixture.nativeElement as HTMLElement;
    expect(page.textContent).toContain('Docukore');
    expect(page.querySelector('select[aria-label]')).not.toBeNull();
  });

  it('switches visible copy and document direction for Arabic', async () => {
    const fixture = TestBed.createComponent(App);
    await fixture.whenStable();
    const select = (fixture.nativeElement as HTMLElement).querySelector('select');
    expect(select).not.toBeNull();
    if (!select) return;
    select.value = 'ar';
    select.dispatchEvent(new Event('change'));
    await fixture.whenStable();
    expect(document.documentElement.lang).toBe('ar');
    expect(document.documentElement.dir).toBe('rtl');
    expect(fixture.nativeElement.textContent).toContain('مساحة');
  });
});
