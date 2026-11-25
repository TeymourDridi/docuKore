package com.docukore.audit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Unit coverage of the payload policy, without framework mocks or persistence infrastructure. */
class AuditPayloadPolicyTest
{
    @ParameterizedTest(name = "allowed field name {index}")
    @ValueSource(strings = {"first_name", "last_name", "birth_date", "diagnosis", "clinical_notes", "prescription",
                            "document_content", "status", "firstName", "lastName", "birthDate", "clinicalNotes",
                            "documentContent"})
    void tracksOnlyKnownFieldNames(String name)
    {
        AuditEvent event = new AuditEvent();
        event.setChangedFields("[\"" + name + "\"]");
        assertDoesNotThrow(() -> AuditPayloadPolicy.validate(event));
    }
    @ParameterizedTest(name = "valid metadata shape {index}")
    @ValueSource(strings = {"{}", "{\"outcome\":\"SUCCESS\"}",
                            "{\"requestId\":\"12345678-1234-4abc-8def-123456789abc\"}",
                            "{\"requestId\":\"12345678-1234-4abc-8def-123456789abc\",\"outcome\":\"SUCCESS\"}"})
    void acceptsOptionalRestrictedMetadata(String metadata)
    {
        AuditEvent event = new AuditEvent();
        event.setChangedFields(" [\"first_name\",\"diagnosis\",\"clinical_notes\"] ");
        event.setMetadata(metadata);
        assertDoesNotThrow(() -> AuditPayloadPolicy.validate(event));
    }
    @ParameterizedTest(name = "forbidden snapshot case {index}")
    @ValueSource(strings = {"", " ", "null", "{}", "[]", "false", "malformed",
                            "{\"clinical_notes\":\"FICTIONAL_CLINICAL_CANARY\"}"})
    void rejectsEveryNonNullSnapshot(String payload)
    {
        AuditEvent event = new AuditEvent();
        event.setPreviousValues(payload);
        assertRejected(event);
        event.setPreviousValues(null);
        event.setNewValues(payload);
        assertRejected(event);
    }
    @ParameterizedTest(name = "invalid changed fields case {index}")
    @ValueSource(strings = {"", " ", "null", "{}", "true", "\"status\"", "[", "[\"status\",]",
                            "[null]", "[1]", "[true]", "[[]]", "[{\"diagnosis\":\"FICTIONAL_CANARY\"}]",
                            "[\"unknown_field\"]", "[\"FICTIONAL_PATIENT_NAME\"]", "[\"status=FICTIONAL_CANARY\"]",
                            "[\"status\"] []", "[\"status\"] /* comment */"})
    void rejectsMalformedNestedAndSensitiveChangedFields(String payload)
    {
        AuditEvent event = new AuditEvent();
        event.setChangedFields(payload);
        assertRejected(event);
    }
    @ParameterizedTest(name = "invalid metadata case {index}")
    @ValueSource(strings = {"", " ", "null", "[]", "false", "{", "{} {}", "{\"outcome\":\"SUCCESS\",}",
                            "{\"clinical_notes\":\"FICTIONAL_CANARY\"}", "{\"outcome\":\"FAILURE\"}",
                            "{\"outcome\":\"success\"}", "{\"outcome\":\"FICTIONAL_CANARY\"}",
                            "{\"outcome\":null}", "{\"outcome\":1}", "{\"outcome\":true}", "{\"outcome\":[]}",
                            "{\"outcome\":{\"diagnosis\":\"FICTIONAL_CANARY\"}}", "{\"requestId\":null}",
                            "{\"requestId\":\"1-1-1-1-1\"}", "{\"requestId\":\"FICTIONAL_CANARY\"}",
                            "{\"requestId\":\"12345678-1234-4ABC-8DEF-123456789ABC\"}",
                            "{\"requestId\":\" 12345678-1234-4abc-8def-123456789abc\"}",
                            "{\"outcome\":\"FICTIONAL_CANARY\",\"outcome\":\"SUCCESS\"}"})
    void rejectsUnknownKeysMalformedShapesAndUnrestrictedMetadataValues(String payload)
    {
        AuditEvent event = new AuditEvent();
        event.setMetadata(payload);
        assertRejected(event);
    }
    @Test
    void boundsJsonDocumentsAndChangedFieldCount()
    {
        AuditEvent event = new AuditEvent();
        event.setChangedFields("[]" + " ".repeat(4094));
        event.setMetadata("{}" + " ".repeat(4094));
        assertDoesNotThrow(() -> AuditPayloadPolicy.validate(event));
        event.setChangedFields("[]" + " ".repeat(4095));
        assertRejected(event);
        event.setChangedFields("[\"status\"" + ",\"status\"".repeat(31) + "]");
        assertDoesNotThrow(() -> AuditPayloadPolicy.validate(event));
        event.setMetadata("{}" + " ".repeat(4095));
        assertRejected(event);
        event.setMetadata(null);
        event.setChangedFields("[\"status\"" + ",\"status\"".repeat(32) + "]");
        assertRejected(event);
    }
    @ParameterizedTest
    @ValueSource(strings = {"changedFields", "previousValues", "newValues", "metadata"})
    void setterMutationAfterValidationCannotEvadeRevalidation(String field)
    {
        AuditEvent event = new AuditEvent();
        assertDoesNotThrow(() -> AuditPayloadPolicy.validate(event));
        assertRejected(null);
        Consumer<String> setter = switch (field)
        {
            case "changedFields" -> event::setChangedFields;
            case "previousValues" -> event::setPreviousValues;
            case "newValues" -> event::setNewValues;
            default -> event::setMetadata;
        };
        setter.accept("{\"diagnosis\":\"FICTIONAL_CLINICAL_CANARY\"}");
        assertRejected(event);
    }

    private static void assertRejected(AuditEvent event)
    {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> AuditPayloadPolicy.validate(event));
        assertEquals("Audit payload rejected", failure.getMessage());
        assertNull(failure.getCause());
    }
}
