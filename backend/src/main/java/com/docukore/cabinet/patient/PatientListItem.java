package com.docukore.cabinet.patient;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Directory identity without clinical notes or persistence metadata.
 *
 * @param id the patient UUID
 * @param patientNumber the cabinet-local patient number
 * @param firstName the legal given name
 * @param familyName the legal family name
 * @param phone the preferred current canonical PHONE/MOBILE contact, or null when absent
 * @param dateOfBirth the complete birth date, or null when unknown or only partially known
 * @param createdAt the patient creation instant
 */
public record PatientListItem(UUID id, String patientNumber, String firstName, String familyName,
                              String phone, LocalDate dateOfBirth, Instant createdAt)
{
}
