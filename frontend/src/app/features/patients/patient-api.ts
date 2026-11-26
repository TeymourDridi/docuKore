import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import {
  CreatePatientRequest,
  PatientDetail,
  PatientId,
  PatientListQuery,
  PatientPage,
  UpdatePatientRequest,
} from './patient.model';

type Mutable<T> = { -readonly [K in keyof T]: T[K] };

/** Patient HTTP access; authorization, tenant selection and validation stay authoritative on the backend. */
@Injectable({ providedIn: 'root' })
export class PatientApi {
  private readonly http = inject(HttpClient);

  listPatients(query: PatientListQuery = {}): Observable<PatientPage> {
    let params = new HttpParams();
    if (query.page !== undefined) params = params.set('page', query.page);
    if (query.size !== undefined) params = params.set('size', query.size);
    const search = query.search?.trim();
    if (search) params = params.set('search', search);
    return this.http.get<PatientPage>('/api/patients', { params });
  }

  getPatient(patientId: PatientId): Observable<PatientDetail> {
    return this.http.get<PatientDetail>(`/api/patients/${patientId}`);
  }

  createPatient(request: CreatePatientRequest): Observable<PatientDetail> {
    return this.http.post<PatientDetail>('/api/patients', request);
  }

  updatePatient(patientId: PatientId, request: UpdatePatientRequest): Observable<PatientDetail> {
    return this.http.patch<PatientDetail>(`/api/patients/${patientId}`, patchDocument(request));
  }
}

/** Copy only explicitly supplied editable fields; undefined stays absent and null stays an explicit clear. */
function patchDocument(request: UpdatePatientRequest): UpdatePatientRequest {
  const document: Mutable<UpdatePatientRequest> = {};
  if (request.firstName !== undefined) document.firstName = request.firstName;
  if (request.familyName !== undefined) document.familyName = request.familyName;
  if (request.phone !== undefined) document.phone = request.phone;
  if (request.dateOfBirth !== undefined) document.dateOfBirth = request.dateOfBirth;
  if (request.initialNote !== undefined) document.initialNote = request.initialNote;
  return document;
}
