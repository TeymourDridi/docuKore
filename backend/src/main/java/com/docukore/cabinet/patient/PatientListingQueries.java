package com.docukore.cabinet.patient;

/** Cabinet-scoped scalar patient listing fragment for the existing patient repository. */
public interface PatientListingQueries
{
    /**
     * Lists one bounded page inside an already authorized, joined tenant transaction.
     *
     * @param page the validated zero-based page number
     * @param size the validated page size, at most 100
     * @param search the stripped, bounded literal search term, or null for no search
     * @return the projected page and exact matching count
     */
    PatientPageResponse findPage(int page, int size, String search);
}
