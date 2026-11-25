package com.docukore.cabinet.patient;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;

/**
 * Patient creation input without client-controlled cabinet or actor identifiers.
 * Calendar and phone semantics are validated by the application service.
 *
 * @param firstName the required given name, stripped of surrounding whitespace
 * @param familyName the required family name, stripped of surrounding whitespace
 * @param phone the required phone input, stripped of surrounding whitespace
 * @param dateOfBirth an optional positive-year ISO date, or {@code null} when absent or blank
 * @param initialNote an optional initial note, or {@code null} when absent or blank
 */
public record CreatePatientRequest(
    @JsonDeserialize(using = StrictTextDeserializer.class)
    @NotBlank @Size(max = 255) String firstName,
    @JsonDeserialize(using = StrictTextDeserializer.class)
    @NotBlank @Size(max = 255) String familyName,
    @JsonDeserialize(using = StrictTextDeserializer.class)
    @NotBlank @Size(max = 255) String phone,
    @JsonDeserialize(using = StrictTextDeserializer.class)
    @Size(max = 10) @Pattern(regexp = "(?!0000)[0-9]{4}-[0-9]{2}-[0-9]{2}") String dateOfBirth,
    @JsonDeserialize(using = StrictTextDeserializer.class)
    @Size(max = 10000) String initialNote)
{
    public CreatePatientRequest
    {
        firstName = strip(firstName);
        familyName = strip(familyName);
        phone = strip(phone);
        dateOfBirth = optional(dateOfBirth);
        initialNote = optional(initialNote);
    }

    private static String strip(String value)
    {
        return value == null ? null : value.strip();
    }

    private static String optional(String value)
    {
        String stripped = strip(value);
        return stripped == null || stripped.isBlank() ? null : stripped;
    }

    @Override
    public String toString()
    {
        return "CreatePatientRequest[redacted]";
    }

    /** Accepts only JSON strings or null; scalar coercion is forbidden for this request's text fields. */
    public static class StrictTextDeserializer extends ValueDeserializer<String>
    {
        @Override
        public String deserialize(JsonParser parser, DeserializationContext context)
        {
            if (parser.currentToken() == JsonToken.VALUE_STRING)
            {
                return parser.getString();
            }
            if (parser.currentToken() == JsonToken.VALUE_NULL)
            {
                return null;
            }
            return context.reportInputMismatch(String.class, "Expected a JSON string or null.");
        }
    }
}
