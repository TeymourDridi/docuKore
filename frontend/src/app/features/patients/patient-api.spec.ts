import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { PatientApi } from './patient-api';
import { PatientDetail, UpdatePatientRequest } from './patient.model';

const endpoint = '/api/patients';
const patientId = '11111111-1111-4111-8111-111111111111';

const listItem = {
  id: patientId,
  patientNumber: 'P-000123',
  firstName: 'Fictional',
  familyName: 'Patient',
  phone: '+21622345678',
  dateOfBirth: '1988-02-29',
  createdAt: '2024-06-15T12:00:00.123456Z',
};
const detail: PatientDetail = { ...listItem, initialNote: 'Fictional note.' };
const page = { content: [listItem], page: 0, size: 20, totalElements: 1, totalPages: 1 };

describe('PatientApi', () => {
  let api: PatientApi;
  let http: HttpTestingController;
  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    api = TestBed.inject(PatientApi);
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => {
    http.verify();
  });

  it('lists patients without query parameters so the backend applies its own defaults', () => {
    let result: unknown;
    api.listPatients({}).subscribe((response) => {
      result = response;
    });
    const request = http.expectOne((candidate) => candidate.url === endpoint);
    expect(request.request.method).toBe('GET');
    expect(request.request.params.keys()).toEqual([]);
    request.flush(page);
    expect(result).toEqual(page);
  });

  it('lists patients with explicit pagination and trimmed search', () => {
    api.listPatients({ page: 2, size: 50, search: '  Fictional Patient  ' }).subscribe();
    const request = http.expectOne((candidate) => candidate.url === endpoint);
    expect(request.request.params.get('page')).toBe('2');
    expect(request.request.params.get('size')).toBe('50');
    expect(request.request.params.get('search')).toBe('Fictional Patient');
    request.flush(page);
  });

  it('omits a blank search instead of sending an empty filter', () => {
    api.listPatients({ search: '   ' }).subscribe();
    const request = http.expectOne((candidate) => candidate.url === endpoint);
    expect(request.request.params.has('search')).toBe(false);
    request.flush(page);
  });

  it('retrieves patient details by UUID', () => {
    let result: PatientDetail | undefined;
    api.getPatient(patientId).subscribe((response) => {
      result = response;
    });
    const request = http.expectOne(`${endpoint}/${patientId}`);
    expect(request.request.method).toBe('GET');
    request.flush(detail);
    expect(result).toEqual(detail);
  });

  it('creates a patient with the exact request body', () => {
    const body = {
      firstName: 'Fictional',
      familyName: 'Patient',
      phone: '00 33 1 23 45 67 89',
      dateOfBirth: '1988-02-29',
    };
    api.createPatient(body).subscribe();
    const request = http.expectOne(endpoint);
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual(body);
    request.flush(detail, { status: 201, statusText: 'Created' });
  });

  it('updates only the supplied fields and omits the rest', () => {
    api.updatePatient(patientId, { firstName: 'Fictional' }).subscribe();
    const request = http.expectOne(`${endpoint}/${patientId}`);
    expect(request.request.method).toBe('PATCH');
    expect(request.request.body).toEqual({ firstName: 'Fictional' });
    expect(JSON.stringify(request.request.body)).not.toContain('dateOfBirth');
    expect(JSON.stringify(request.request.body)).not.toContain('initialNote');
    request.flush(detail);
  });

  it('keeps an explicit null in the PATCH body to clear an optional field', () => {
    api.updatePatient(patientId, { dateOfBirth: null }).subscribe();
    const request = http.expectOne(`${endpoint}/${patientId}`);
    expect(request.request.body).toEqual({ dateOfBirth: null });
    expect(JSON.stringify(request.request.body)).toContain('"dateOfBirth":null');
    request.flush(detail);
  });

  it('treats an undefined field as omitted and never turns it into null', () => {
    api.updatePatient(patientId, { firstName: 'Fictional', dateOfBirth: undefined }).subscribe();
    const request = http.expectOne(`${endpoint}/${patientId}`);
    expect('dateOfBirth' in request.request.body).toBe(false);
    expect(request.request.body).toEqual({ firstName: 'Fictional' });
    request.flush(detail);
  });

  it('never forwards persistence metadata from a wider patient object', () => {
    // A caller could mistakenly pass a full detail row; only editable fields may reach the wire.
    const widened = {
      firstName: 'Fictional',
      patientNumber: 'P-000123',
      createdAt: '2024-06-15T12:00:00.123456Z',
    } as UpdatePatientRequest;
    api.updatePatient(patientId, widened).subscribe();
    const request = http.expectOne(`${endpoint}/${patientId}`);
    expect(request.request.body).toEqual({ firstName: 'Fictional' });
    request.flush(detail);
  });

  it('propagates backend error responses without changing their meaning', () => {
    let status = 0;
    let code = '';
    api.updatePatient(patientId, { phone: '+21622345678' }).subscribe({
      error: (error) => {
        status = error.status;
        code = error.error.code;
      },
    });
    http
      .expectOne(`${endpoint}/${patientId}`)
      .flush(
        { code: 'PHONE_CONFLICT', message: 'This phone number is already in use.' },
        { status: 409, statusText: 'Conflict' }
      );
    expect(status).toBe(409);
    expect(code).toBe('PHONE_CONFLICT');
  });
});
