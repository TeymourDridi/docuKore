package com.docukore.cabinet.patient;

import java.util.List;

/**
 * Explicit directory pagination contract, independent of Spring's Page serialization.
 *
 * @param content the bounded patient results in deterministic newest-first order
 * @param page the requested zero-based page
 * @param size the requested page size
 * @param totalElements the total number of matching cabinet patients
 * @param totalPages the total matching pages, zero when there are no matches
 */
public record PatientPageResponse(List<PatientListItem> content, int page, int size,
                                  long totalElements, int totalPages)
{
    public PatientPageResponse
    {
        content = List.copyOf(content);
    }
}
