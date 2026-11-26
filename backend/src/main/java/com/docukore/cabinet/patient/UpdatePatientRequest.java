package com.docukore.cabinet.patient;

import com.fasterxml.jackson.annotation.JsonCreator;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Presence-aware PATCH document: omitted keys and explicit null remain distinct without a new dependency.
 * Only the five public editable fields are accepted; persistence metadata is never writable.
 *
 * @param fields the supplied JSON properties, including explicit null values
 */
public record UpdatePatientRequest(Map<String, Object> fields)
{
    /** Public editable names, never cabinet, actor or immutable persistence identifiers. */
    private static final Set<String> EDITABLE =
        Set.of("firstName", "familyName", "phone", "dateOfBirth", "initialNote");

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public UpdatePatientRequest
    {
        fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
    }

    boolean has(String field)
    {
        return fields.containsKey(field);
    }

    CreatePatientRequest normalized()
    {
        if (!EDITABLE.containsAll(fields.keySet()))
        {
            throw new PatientCreationService.InvalidInput(null);
        }
        var request = new CreatePatientRequest(text("firstName"), text("familyName"), text("phone"),
            text("dateOfBirth"), text("initialNote"));
        required("firstName", request.firstName());
        required("familyName", request.familyName());
        if (has("phone") && request.phone() != null)
        {
            required("phone", request.phone());
        }
        String birth = request.dateOfBirth();
        if (birth != null && !birth.matches("(?!0000)[0-9]{4}-[0-9]{2}-[0-9]{2}"))
        {
            throw new PatientCreationService.InvalidInput("dateOfBirth");
        }
        if (request.initialNote() != null && request.initialNote().length() > 10000)
        {
            throw new PatientCreationService.InvalidInput("initialNote");
        }
        return request;
    }

    private String text(String field)
    {
        Object value = fields.get(field);
        if (value != null && !(value instanceof String))
        {
            throw new PatientCreationService.InvalidInput(field);
        }
        return (String) value;
    }

    private void required(String field, String value)
    {
        if (has(field) && (value == null || value.isBlank() || value.length() > 255))
        {
            throw new PatientCreationService.InvalidInput(field);
        }
    }

    @Override
    public String toString()
    {
        return "UpdatePatientRequest[redacted]";
    }
}
