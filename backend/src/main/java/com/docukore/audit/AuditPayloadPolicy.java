package com.docukore.audit;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Restricts untrusted audit JSON to field names and fixed correlation/outcome shapes.
 * Each optional JSON document is limited to 4096 UTF-8 bytes; changes allow at most 32 names.
 * Metadata keys are optional; a UUID's shape does not prove server provenance or authorization.
 * Callers must validate immediately before persistence; direct repository writes bypass this policy.
 */
final class AuditPayloadPolicy
{
    /** Maximum UTF-8 size of each optional JSON document. */
    private static final int MAX_JSON_BYTES = 4096;
    /** Maximum number of tracked field names per event. */
    private static final int MAX_CHANGED_FIELDS = 32;
    /** Permitted field names only, never clinical field values. */
    private static final Set<String> FIELD_NAMES = Set.of(
            "first_name", "last_name", "birth_date", "diagnosis", "clinical_notes", "prescription",
            "document_content", "status", "firstName", "lastName", "birthDate", "clinicalNotes", "documentContent");
    /** Canonical lowercase UUID syntax; does not establish trusted provenance. */
    private static final Pattern REQUEST_ID = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    /** Strict immutable parser rejecting duplicate object keys and trailing tokens. */
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();
    private AuditPayloadPolicy()
    {
        // Static validation only; no application state or injected dependencies.
    }
    /**
     * Validate current event values, including values assigned directly through entity setters.
     * Never retains payloads, logs input, or exposes parser messages or exception causes.
     *
     * @param event the audit event to inspect
     * @throws IllegalArgumentException if the event or any JSON payload violates the policy
     */
    static void validate(AuditEvent event)
    {
        if (event == null || event.previousValues() != null || event.newValues() != null)
        {
            throw rejected();
        }
        if (event.changedFields() != null)
        {
            validateChangedFields(parse(event.changedFields()));
        }
        if (event.metadata() != null)
        {
            validateMetadata(parse(event.metadata()));
        }
    }

    private static Object parse(String payload)
    {
        if (payload.length() > MAX_JSON_BYTES || payload.getBytes(StandardCharsets.UTF_8).length > MAX_JSON_BYTES)
        {
            throw rejected();
        }
        try
        {
            return JSON.readValue(payload, Object.class);
        } catch (RuntimeException ignored)
        {
            throw rejected();
        }
    }

    private static void validateChangedFields(Object payload)
    {
        if (!(payload instanceof List<?> fields) || fields.size() > MAX_CHANGED_FIELDS)
        {
            throw rejected();
        }
        for (Object field : fields)
        {
            if (!(field instanceof String name) || !FIELD_NAMES.contains(name))
            {
                throw rejected();
            }
        }
    }

    private static void validateMetadata(Object payload)
    {
        if (!(payload instanceof Map<?, ?> metadata))
        {
            throw rejected();
        }
        for (var entry : metadata.entrySet())
        {
            if (!(entry.getValue() instanceof String value))
            {
                throw rejected();
            }
            boolean allowed = "requestId".equals(entry.getKey())
                    ? REQUEST_ID.matcher(value).matches()
                    : "outcome".equals(entry.getKey()) && "SUCCESS".equals(value);
            if (!allowed)
            {
                throw rejected();
            }
        }
    }

    private static IllegalArgumentException rejected()
    {
        return new IllegalArgumentException("Audit payload rejected");
    }
}
