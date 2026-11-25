package com.docukore.cabinet.patient;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Persisted patient detail with only the eight public API fields.
 *
 * @param id the patient UUID
 * @param patientNumber the cabinet-local patient number
 * @param firstName the persisted legal given name
 * @param familyName the persisted legal family name
 * @param phone the preferred current canonical PHONE/MOBILE contact, or null when absent
 * @param dateOfBirth the complete birth date, or null when unknown or only partially known
 * @param initialNote the unmodified persisted initial note, or null when absent
 * @param createdAt the persisted patient creation instant
 */
public record PatientDetailResponse(
    UUID id,
    String patientNumber,
    String firstName,
    String familyName,
    String phone,
    LocalDate dateOfBirth,
    String initialNote,
    Instant createdAt)
{
}
