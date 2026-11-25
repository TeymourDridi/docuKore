package com.docukore.cabinet.patient;

import org.springframework.data.repository.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Repository for Patient entities.
 *
 * <p>Provides basic persistence operations for patients within a cabinet.
 * All operations are scoped to the current tenant context.</p>
 */
public interface PatientRepository extends Repository<Patient, UUID>, PatientListingQueries
{

    /**
     * Saves a patient.
     *
     * @param patient patient to save
     * @param <S>     patient subtype
     * @return saved patient
     */
    @Transactional(transactionManager = "transactionManager")
    <S extends Patient> S save(S patient);

    /**
     * Finds a patient by ID.
     *
     * @param id patient ID
     * @return patient if found
     */
    @Transactional(transactionManager = "transactionManager", readOnly = true)
    Optional<Patient> findById(UUID id);
}