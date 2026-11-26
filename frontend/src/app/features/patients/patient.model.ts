/** A patient UUID assigned by the backend. */
export type PatientId = string;
/** A cabinet-local patient number assigned by the backend. */
export type PatientNumber = string;
/** A calendar date in the backend's yyyy-MM-dd wire format. */
export type BirthDate = string;
/** An ISO-8601 UTC instant as serialized by the backend. */
export type Instant = string;

/** Directory row returned by GET /api/patients. */
export interface PatientListItem {
  readonly id: PatientId;
  readonly patientNumber: PatientNumber;
  readonly firstName: string;
  readonly familyName: string;
  readonly phone: string | null;
  readonly dateOfBirth: BirthDate | null;
  readonly createdAt: Instant;
}

/** Persisted detail returned by GET/PATCH /api/patients/{patientId} and on creation. */
export interface PatientDetail extends PatientListItem {
  readonly initialNote: string | null;
}

/** Explicit directory page, independent of any Spring Page serialization. */
export interface PatientPage {
  readonly content: readonly PatientListItem[];
  readonly page: number;
  readonly size: number;
  readonly totalElements: number;
  readonly totalPages: number;
}

/** Optional directory query; absent values use the backend defaults (page 0, size 20). */
export interface PatientListQuery {
  readonly page?: number;
  readonly size?: number;
  readonly search?: string;
}

/** Creation input; the backend assigns identifiers and rejects a missing phone. */
export interface CreatePatientRequest {
  readonly firstName: string;
  readonly familyName: string;
  readonly phone: string;
  readonly dateOfBirth?: BirthDate | null;
  readonly initialNote?: string | null;
}

/**
 * Presence-aware PATCH document over the five editable fields only.
 * An absent (or undefined) property means unchanged; an explicit null clears a supported
 * optional field. Names are never nullable. Build this from edited values, never from
 * PatientDetail: a null dateOfBirth may hide an existing partial birth date.
 */
export interface UpdatePatientRequest {
  readonly firstName?: string;
  readonly familyName?: string;
  readonly phone?: string | null;
  readonly dateOfBirth?: BirthDate | null;
  readonly initialNote?: string | null;
}
