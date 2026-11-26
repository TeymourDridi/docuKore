import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { vi } from 'vitest';
import { LanguageService } from '../../../i18n/language.service';
import { PatientList } from './patient-list';
import { PatientListItem, PatientPage } from '../patient.model';

const endpoint = '/api/patients';
const firstPatient: PatientListItem = {
  id: '11111111-1111-4111-8111-111111111111',
  patientNumber: 'P-000123',
  firstName: 'Fictional',
  familyName: 'Patient',
  phone: '+21622345678',
  dateOfBirth: '1988-02-29',
  createdAt: '2024-06-15T12:00:00.123456Z',
};
const secondPatient: PatientListItem = {
  ...firstPatient,
  id: '22222222-2222-4222-8222-222222222222',
  patientNumber: 'P-000124',
  firstName: 'Amira',
  familyName: 'Example',
  phone: null,
  dateOfBirth: null,
};

function page(
  content: readonly PatientListItem[],
  currentPage = 0,
  totalPages = 1,
  totalElements = content.length
): PatientPage {
  return { content, page: currentPage, size: 20, totalElements, totalPages };
}

describe('PatientList', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [PatientList],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    TestBed.inject(LanguageService).select('en');
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    http.verify();
    TestBed.inject(LanguageService).select('fr');
    vi.useRealTimers();
  });

  function createFixture(): ComponentFixture<PatientList> {
    const fixture = TestBed.createComponent(PatientList);
    fixture.detectChanges();
    return fixture;
  }

  function loadFirstPage(fixture: ComponentFixture<PatientList>, response = page([firstPatient])) {
    const request = http.expectOne((candidate) => candidate.url === endpoint);
    request.flush(response);
    fixture.detectChanges();
  }

  function searchInput(fixture: ComponentFixture<PatientList>, value: string): HTMLInputElement {
    const input = fixture.nativeElement.querySelector('input[type="search"]') as HTMLInputElement;
    input.value = value;
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    return input;
  }

  it('loads the first page using backend defaults and renders the patient directory', () => {
    const fixture = createFixture();
    const request = http.expectOne((candidate) => candidate.url === endpoint);
    expect(request.request.method).toBe('GET');
    expect(request.request.params.keys()).toEqual([]);
    request.flush(page([firstPatient, secondPatient]));
    fixture.detectChanges();

    const element: HTMLElement = fixture.nativeElement;
    expect(element.querySelector('h1')?.textContent).toContain('Patients');
    expect(element.querySelectorAll('thead th')).toHaveLength(5);
    expect(element.querySelectorAll('tbody tr')).toHaveLength(2);
    expect(element.textContent).toContain('Page 1 of 1');
    const pagination = element.querySelectorAll('nav button') as NodeListOf<HTMLButtonElement>;
    expect(pagination).toHaveLength(2);
    expect(pagination[0].disabled).toBe(true);
    expect(pagination[1].disabled).toBe(true);
    expect(element.textContent).toContain('P-000123');
    expect(element.querySelector('tbody td:nth-child(2)')?.textContent).toBe('Fictional');
    expect(element.querySelector('tbody td:nth-child(3)')?.textContent).toBe('Patient');
    expect(element.textContent).toContain('1988-02-29');
    expect(element.textContent).toContain('+21622345678');
  });

  it('shows translated placeholders for nullable fields', () => {
    const fixture = createFixture();
    loadFirstPage(fixture, page([secondPatient]));

    expect(fixture.nativeElement.textContent).toContain('Not provided');
    expect(fixture.nativeElement.querySelectorAll('tbody tr')).toHaveLength(1);
  });

  it('debounces search input and sends the trimmed term', async () => {
    vi.useFakeTimers();
    const fixture = createFixture();
    loadFirstPage(fixture);
    searchInput(fixture, '  Amira Example  ');

    http.expectNone((candidate) => candidate.url === endpoint);
    await vi.advanceTimersByTimeAsync(299);
    http.expectNone((candidate) => candidate.url === endpoint);
    await vi.advanceTimersByTimeAsync(1);

    const request = http.expectOne((candidate) => candidate.url === endpoint);
    expect(request.request.params.get('search')).toBe('Amira Example');
    expect(request.request.params.has('page')).toBe(false);
    expect(request.request.params.has('size')).toBe(false);
    request.flush(page([secondPatient]));
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('tbody td:nth-child(2)')?.textContent).toBe('Amira');
    expect(fixture.nativeElement.querySelector('tbody td:nth-child(3)')?.textContent).toBe(
      'Example'
    );
  });

  it('cancels outdated requests, clears stale rows, and resets to page zero for a new search', async () => {
    vi.useFakeTimers();
    const fixture = createFixture();
    loadFirstPage(fixture, page([firstPatient], 0, 3, 41));

    const next = fixture.nativeElement.querySelector(
      '[aria-label="Next page"]'
    ) as HTMLButtonElement;
    next.click();
    fixture.detectChanges();
    const outdated = http.expectOne((candidate) => candidate.url === endpoint);
    expect(outdated.request.params.get('page')).toBe('1');

    searchInput(fixture, '  Amira  ');
    expect(outdated.cancelled).toBe(true);
    expect(fixture.nativeElement.querySelectorAll('tbody tr')).toHaveLength(0);
    await vi.advanceTimersByTimeAsync(300);

    const request = http.expectOne((candidate) => candidate.url === endpoint);
    expect(request.request.params.get('search')).toBe('Amira');
    expect(request.request.params.has('page')).toBe(false);
    request.flush(page([secondPatient]));
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('tbody td:nth-child(2)')?.textContent).toBe('Amira');
    expect(fixture.nativeElement.querySelector('tbody td:nth-child(3)')?.textContent).toBe(
      'Example'
    );
    expect(fixture.nativeElement.textContent).not.toContain('Fictional Patient');
  });

  it('clears the search filter and preserves it while moving between pages', async () => {
    vi.useFakeTimers();
    const fixture = createFixture();
    loadFirstPage(fixture, page([secondPatient], 0, 2, 21));
    let controls = fixture.nativeElement.querySelectorAll(
      'nav button'
    ) as NodeListOf<HTMLButtonElement>;
    expect(controls[0].disabled).toBe(true);
    expect(controls[1].disabled).toBe(false);

    searchInput(fixture, 'Amira');
    await vi.advanceTimersByTimeAsync(300);
    http
      .expectOne((candidate) => candidate.url === endpoint)
      .flush(page([secondPatient], 0, 2, 21));
    fixture.detectChanges();

    (fixture.nativeElement.querySelector('[aria-label="Next page"]') as HTMLButtonElement).click();
    fixture.detectChanges();
    const nextRequest = http.expectOne((candidate) => candidate.url === endpoint);
    expect(nextRequest.request.params.get('page')).toBe('1');
    expect(nextRequest.request.params.get('search')).toBe('Amira');
    nextRequest.flush(page([secondPatient], 1, 2, 21));
    fixture.detectChanges();
    controls = fixture.nativeElement.querySelectorAll(
      'nav button'
    ) as NodeListOf<HTMLButtonElement>;
    expect(controls[0].disabled).toBe(false);
    expect(controls[1].disabled).toBe(true);

    (
      fixture.nativeElement.querySelector('[aria-label="Previous page"]') as HTMLButtonElement
    ).click();
    fixture.detectChanges();
    const previousRequest = http.expectOne((candidate) => candidate.url === endpoint);
    expect(previousRequest.request.params.get('page')).toBe('0');
    expect(previousRequest.request.params.get('search')).toBe('Amira');
    previousRequest.flush(page([secondPatient], 0, 2, 21));
    fixture.detectChanges();

    (
      fixture.nativeElement.querySelector('[aria-label="Clear search"]') as HTMLButtonElement
    ).click();
    fixture.detectChanges();
    await vi.advanceTimersByTimeAsync(300);
    const clearRequest = http.expectOne((candidate) => candidate.url === endpoint);
    expect(clearRequest.request.params.keys()).toEqual([]);
    clearRequest.flush(page([firstPatient]));
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('tbody td:nth-child(2)')?.textContent).toBe(
      'Fictional'
    );
    expect(fixture.nativeElement.querySelector('tbody td:nth-child(3)')?.textContent).toBe(
      'Patient'
    );
  });

  it('renders distinct empty-directory and no-search-results states', async () => {
    vi.useFakeTimers();
    const fixture = createFixture();
    loadFirstPage(fixture, page([], 0, 0, 0));
    expect(fixture.nativeElement.textContent).toContain('No patients yet');

    searchInput(fixture, 'No match');
    await vi.advanceTimersByTimeAsync(300);
    http.expectOne((candidate) => candidate.url === endpoint).flush(page([], 0, 0, 0));
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('No patients match your search');
  });

  it('displays a recoverable error and retries the current directory query', () => {
    const fixture = createFixture();
    http
      .expectOne((candidate) => candidate.url === endpoint)
      .flush(
        { message: 'Backend details must not be shown' },
        { status: 500, statusText: 'Server Error' }
      );
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Patients could not be loaded');
    expect(fixture.nativeElement.textContent).not.toContain('Backend details');

    (
      fixture.nativeElement.querySelector(
        '[aria-label="Retry loading patients"]'
      ) as HTMLButtonElement
    ).click();
    fixture.detectChanges();
    const retry = http.expectOne((candidate) => candidate.url === endpoint);
    expect(retry.request.params.keys()).toEqual([]);
    retry.flush(page([firstPatient]));
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('tbody td:nth-child(2)')?.textContent).toBe(
      'Fictional'
    );
    expect(fixture.nativeElement.querySelector('tbody td:nth-child(3)')?.textContent).toBe(
      'Patient'
    );
  });

  it('fetches the last valid page when the backend echoes a page beyond the actual total', async () => {
    vi.useFakeTimers();
    const fixture = createFixture();
    // The backend echoes the requested page (9) although only 2 pages exist.
    loadFirstPage(fixture, page([firstPatient], 9, 2, 41));

    const request = http.expectOne((candidate) => candidate.url === endpoint);
    expect(request.request.params.get('page')).toBe('1');
    request.flush(page([secondPatient], 1, 2, 41));
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Amira');
    expect(fixture.nativeElement.querySelectorAll('tbody tr')).toHaveLength(1);
    expect(fixture.nativeElement.textContent).toContain('Page 2 of 2');
    expect(fixture.nativeElement.querySelector('[aria-label="Next page"]')?.disabled).toBe(true);
    expect(fixture.nativeElement.querySelector('[aria-label="Previous page"]')?.disabled).toBe(false);
    expect(fixture.nativeElement.textContent).not.toContain('Fictional Patient');
    vi.useRealTimers();
  });

  it('preserves the active search query when redirecting to the last valid page', async () => {
    vi.useFakeTimers();
    const fixture = createFixture();
    loadFirstPage(fixture);
    searchInput(fixture, 'Amira');
    await vi.advanceTimersByTimeAsync(300);
    // The backend still echoes the now-invalid page even though the search narrowed the range.
    http.expectOne((candidate) => candidate.url === endpoint).flush(page([secondPatient], 3, 1, 5));
    const redirect = http.expectOne((candidate) => candidate.url === endpoint);
    expect(redirect.request.params.get('search')).toBe('Amira');
    expect(redirect.request.params.get('page')).toBe('0');
    redirect.flush(page([secondPatient], 0, 1, 5));
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Amira');
    expect(fixture.nativeElement.textContent).toContain('Page 1 of 1');
    expect(fixture.nativeElement.querySelector('[aria-label="Next page"]')?.disabled).toBe(true);
    expect(fixture.nativeElement.querySelector('[aria-label="Previous page"]')?.disabled).toBe(true);
    vi.useRealTimers();
  });

  it('shows the genuine empty state when the directory has no pages', () => {
    const fixture = createFixture();
    loadFirstPage(fixture, page([], 0, 0, 0));

    expect(fixture.nativeElement.textContent).toContain('No patients yet');
    expect(fixture.nativeElement.textContent).not.toContain('Page');
    expect(fixture.nativeElement.querySelectorAll('nav button')).toHaveLength(0);
  });

  it('shows the genuine empty state for a search that matches nothing', async () => {
    vi.useFakeTimers();
    const fixture = createFixture();
    loadFirstPage(fixture);
    searchInput(fixture, 'No match');
    await vi.advanceTimersByTimeAsync(300);
    http.expectOne((candidate) => candidate.url === endpoint).flush(page([], 0, 0, 0));
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('No patients match your search');
    expect(fixture.nativeElement.textContent).not.toContain('No patients yet');
    vi.useRealTimers();
  });

  it('settles on the last page instead of retrying forever when the backend keeps echoing an invalid page', () => {
    const fixture = createFixture();
    loadFirstPage(fixture, page([firstPatient], 9, 2, 41));
    const redirect = http.expectOne((candidate) => candidate.url === endpoint);
    expect(redirect.request.params.get('page')).toBe('1');
    // A second out-of-range answer must not trigger another request.
    redirect.flush(page([firstPatient], 9, 2, 41));
    fixture.detectChanges();
    http.expectNone((candidate) => candidate.url === endpoint);
    expect(fixture.nativeElement.querySelectorAll('tbody tr')).toHaveLength(0);
    expect(fixture.nativeElement.textContent).toContain('Page 2 of 2');
  });

  it('does not redirect a page that is already inside the valid range', () => {
    const fixture = createFixture();
    loadFirstPage(fixture, page([firstPatient], 1, 3, 61));

    expect(fixture.nativeElement.textContent).toContain('Page 2 of 3');
    expect(fixture.nativeElement.querySelectorAll('tbody tr')).toHaveLength(1);
    expect(fixture.nativeElement.querySelector('[aria-label="Next page"]')?.disabled).toBe(false);
  });

  it('localizes labels, empty states, and pagination in French and Arabic', () => {
    const fixture = createFixture();
    loadFirstPage(fixture, page([firstPatient], 0, 2, 21));
    const i18n = TestBed.inject(LanguageService);
    i18n.select('fr');
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('label')?.textContent).toContain('Rechercher');
    expect(fixture.nativeElement.textContent).toContain('Page suivante');

    i18n.select('ar');
    fixture.detectChanges();
    expect(document.documentElement.dir).toBe('rtl');
    expect(fixture.nativeElement.querySelector('label')?.textContent).toContain('ابحث');
    expect(fixture.nativeElement.textContent).toContain('الصفحة التالية');
  });
});
