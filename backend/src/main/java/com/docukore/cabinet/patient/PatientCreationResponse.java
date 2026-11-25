package com.docukore.cabinet.patient;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Patient creation result with only the public API fields.
 *
 * @param id the newly created patient identifier
 * @param patientNumber the generated cabinet-local patient number
 * @param firstName the patient's stripped given name
 * @param familyName the patient's stripped family name
 * @param phone the normalized phone number
 * @param dateOfBirth the optional complete birth date, or {@code null}
 * @param initialNote the optional initial note, or {@code null}
 * @param createdAt the creation timestamp
 */
public record PatientCreationResponse(
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
