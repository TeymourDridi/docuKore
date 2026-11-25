package com.docukore.cabinet.patient;

import java.util.Optional;
import java.util.UUID;

/** Cabinet-scoped scalar patient detail fragment for the existing patient repository. */
public interface PatientDetailQueries
{
    /**
     * Reads persisted detail inside an already authorized, joined tenant transaction.
     *
     * @param patientId the requested patient UUID, bound as a query value
     * @return the public detail, or empty when the patient is absent from the authorized cabinet
     */
    Optional<PatientDetailResponse> findDetail(UUID patientId);
}
