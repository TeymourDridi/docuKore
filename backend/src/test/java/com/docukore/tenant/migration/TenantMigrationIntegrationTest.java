package com.docukore.tenant.migration;

import com.docukore.support.PostgresMigrationTest;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TenantMigrationIntegrationTest extends PostgresMigrationTest
{

    /** Fixed cabinet UUID for test consistency. */
    private static final UUID CABINET = UUID.fromString("00000000-0000-0000-0000-000000000001");

    /** Test phone number. */
    private static final String PHONE = "+21698765432";

    private UUID patient(String schema, String number, Integer year, Integer month, Integer day, String precision)
    {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO " + schema + ".patients "
            + "(id, cabinet_id, patient_number, legal_given_name, legal_family_name, "
            + "birth_year, birth_month, birth_day, birth_precision, created_at, updated_at) "
            + "VALUES (?, ?, ?, 'Fictional', 'Patient', ?, ?, ?, ?, now(), now())",
            id, CABINET, number, year, month, day, precision);
        return id;
    }

    private UUID patient(String schema, String number)
    {
        return patient(schema, number, null, null, null, "UNKNOWN");
    }

    private UUID contact(String schema, UUID patient, String type, String normalized, Boolean current)
    {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO " + schema + ".patient_contacts "
            + "(id, patient_id, contact_type, value, value_normalized, is_current, created_at) "
            + "VALUES (?, ?, ?, 'original fictional input', ?, ?, now())",
            id, patient, type, normalized, current);
        return id;
    }

    private List<String> history(String schema)
    {
        return jdbc.queryForList(
            "SELECT version FROM " + schema
                + ".flyway_schema_history_tenant WHERE success AND version IS NOT NULL "
                + "ORDER BY installed_rank",
            String.class);
    }

    @Test
    void freshSchemasHaveSeventeenTablesAndIndependentNineVersionHistories()
    {
        String first = tenant("latest");
        String second = tenant("latest");
        assertEquals(List.of("1", "2", "3", "4", "5", "6", "7", "8", "9"), history(first));
        assertEquals(history(first), history(second));
        assertEquals(17, jdbc.queryForObject(
            "SELECT count(*) FROM information_schema.tables WHERE table_schema = ? "
                + "AND table_type = 'BASE TABLE' AND table_name <> 'flyway_schema_history_tenant'",
            Integer.class, first));
        assertEquals(0, jdbc.queryForObject(
            "SELECT count(*) FROM information_schema.tables WHERE table_schema = ? "
                + "AND table_name = 'patients'",
            Integer.class, platformSchema));
    }

    @Test
    void patientNumbersAreUniqueWithinOneCabinetSchemaButReusableInAnother()
    {
        String first = tenant("latest");
        patient(first, "P001");
        assertDatabaseError("23505", "unique_patient_number", () -> patient(first, "P001"));
        patient(tenant("latest"), "P001");
    }

    @ParameterizedTest(name = "active phone rejects invalid normalized input [{index}]")
    @NullAndEmptySource
    @ValueSource(strings = {
        "98765432", "+0123456789", "+216 98765432",
        "+1234567890123456", "+1", "+21698765432\n", "+٢١٦٩٨٧٦٥٤٣٢"
    })
    void activePhonesRequireE164Shape(String normalized)
    {
        String schema = tenant("latest");
        UUID id = patient(schema, "P001");
        assertDatabaseError("23514", "patient_contacts_active_phone_e164",
            () -> contact(schema, id, "PHONE", normalized, true));
        assertDatabaseError("23514", "patient_contacts_active_phone_e164",
            () -> contact(schema, id, "MOBILE", normalized, true));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PHONE", "MOBILE", "EMAIL"})
    void lifecycleCannotBeNullEvenForNonPhoneContacts(String type)
    {
        String schema = tenant("latest");
        UUID id = patient(schema, "P001");
        assertDatabaseError("23502", null, () -> contact(schema, id, type, null, null));
    }

    @Test
    void invalidInactivePhoneCannotBeActivatedAndActiveNormalizationCannotBeCleared()
    {
        String schema = tenant("latest");
        UUID id = patient(schema, "P001");
        UUID inactive = contact(schema, id, "PHONE", null, false);
        assertDatabaseError("23514", "patient_contacts_active_phone_e164",
            () -> jdbc.update(
                "UPDATE " + schema + ".patient_contacts SET is_current = true WHERE id = ?",
                inactive));
        UUID active = contact(schema, id, "MOBILE", PHONE, true);
        assertDatabaseError("23514", "patient_contacts_active_phone_e164",
            () -> jdbc.update(
                "UPDATE " + schema + ".patient_contacts SET value_normalized = NULL WHERE id = ?",
                active));
    }

    @Test
    void inactiveUnnormalizedContactsAndPatientsWithoutPhonesRemainRepresentable()
    {
        String schema = tenant("latest");
        UUID id = patient(schema, "P001");
        UUID noPhone = patient(schema, "P002");
        contact(schema, id, "PHONE", null, false);
        contact(schema, id, "EMAIL", null, true);
        assertEquals(0, jdbc.queryForObject(
            "SELECT count(*) FROM " + schema + ".patient_contacts WHERE patient_id = ?",
            Integer.class, noPhone));
    }

    @Test
    void phoneUniqueAcrossPatientsAndTypesIncludingSamePatientDuplicateRows()
    {
        String schema = tenant("latest");
        UUID first = patient(schema, "P001");
        UUID second = patient(schema, "P002");
        contact(schema, first, "PHONE", PHONE, true);
        assertDatabaseError("23505", "idx_patient_contacts_unique_normalized_phone",
            () -> contact(schema, second, "MOBILE", PHONE, true));
        assertDatabaseError("23505", "idx_patient_contacts_unique_normalized_phone",
            () -> contact(schema, first, "MOBILE", PHONE, true));
        assertEquals(2, jdbc.queryForObject(
            "SELECT count(*) FROM " + schema + ".patients", Integer.class));
    }

    @Test
    void differentNumbersAndIndependentSchemasAreAllowedAndOriginalIsPreserved()
    {
        String first = tenant("latest");
        String second = tenant("latest");
        UUID id = patient(first, "P001");
        UUID original = contact(first, id, "PHONE", PHONE, true);
        contact(first, id, "MOBILE", "+33123456789", true);
        contact(second, patient(second, "P001"), "PHONE", PHONE, true);
        assertEquals("original fictional input", jdbc.queryForObject(
            "SELECT value FROM " + first + ".patient_contacts WHERE id = ?",
            String.class, original));
        assertEquals(2, jdbc.queryForObject(
            "SELECT count(*) FROM " + first + ".patient_contacts", Integer.class));
    }

    @Test
    void deactivationAllowsReuseButReactivationOfOldAssignmentIsRejected()
    {
        String schema = tenant("latest");
        UUID original = contact(schema, patient(schema, "P001"), "PHONE", PHONE, true);
        jdbc.update("UPDATE " + schema + ".patient_contacts SET is_current = false WHERE id = ?", original);
        contact(schema, patient(schema, "P002"), "PHONE", PHONE, true);
        assertDatabaseError("23505", "idx_patient_contacts_unique_normalized_phone",
            () -> jdbc.update("UPDATE " + schema + ".patient_contacts SET is_current = true WHERE id = ?", original));
        assertEquals(2, jdbc.queryForObject(
            "SELECT count(*) FROM " + schema + ".patient_contacts", Integer.class));
    }

    @ParameterizedTest
    @CsvSource(nullValues = "NULL", value = {
        "2000,2,29,COMPLETE_DATE", "2024,2,29,COMPLETE_DATE", "1899,12,31,COMPLETE_DATE",
        "2400,2,29,COMPLETE_DATE", "2024,2,NULL,YEAR_MONTH", "1800,NULL,NULL,YEAR_ONLY", "NULL,NULL,NULL,UNKNOWN"
    })
    void validCalendarAndPartialDatesPreservePrecision(Integer year, Integer month, Integer day, String precision)
    {
        String schema = tenant("latest");
        UUID id = patient(schema, "P001", year, month, day, precision);
        assertEquals(day, jdbc.queryForObject(
            "SELECT birth_day FROM " + schema + ".patients WHERE id = ?",
            Integer.class, id));
        assertEquals(precision, jdbc.queryForObject(
            "SELECT birth_precision FROM " + schema + ".patients WHERE id = ?",
            String.class, id));
    }

    @ParameterizedTest
    @CsvSource(nullValues = "NULL", value = {
        "2023,2,29,COMPLETE_DATE", "1900,2,29,COMPLETE_DATE", "2100,2,29,COMPLETE_DATE",
        "2024,2,31,COMPLETE_DATE", "2024,4,31,COMPLETE_DATE", "2024,13,1,COMPLETE_DATE",
        "2024,1,0,COMPLETE_DATE", "2024,1,NULL,COMPLETE_DATE", "NULL,1,1,COMPLETE_DATE",
        "2024,13,NULL,YEAR_MONTH", "2024,1,1,YEAR_MONTH", "2024,NULL,NULL,YEAR_MONTH",
        "2024,1,NULL,YEAR_ONLY", "NULL,NULL,NULL,YEAR_ONLY", "2024,NULL,NULL,UNKNOWN", "0,NULL,NULL,YEAR_ONLY"
    })
    void invalidCalendarOrPrecisionIsRejected(Integer year, Integer month, Integer day, String precision)
    {
        String schema = tenant("latest");
        assertDatabaseError("23514", "valid_birth_date", () -> patient(schema, "P001", year, month, day, precision));
    }

    private void allergy(String schema, UUID id)
    {
        jdbc.update("INSERT INTO " + schema + ".patient_allergies " +
            "(id, patient_id, allergen_type, allergen_name, reaction_severity, created_at) " +
            "VALUES (?, ?, 'DRUG', 'Fictional allergen', 'UNKNOWN', now())", UUID.randomUUID(), id);
    }

    @Test
    void unknownAllergySeverityIsNotNoKnownAllergiesAndDeletionIsRestricted()
    {
        String schema = tenant("latest");
        UUID id = patient(schema, "P001");
        allergy(schema, id);
        assertDatabaseError("23001", "patient_allergies_patient_id_fkey",
            () -> jdbc.update(
                "DELETE FROM " + schema + ".patients WHERE id = ?", id));
        assertEquals(1, jdbc.queryForObject(
            "SELECT count(*) FROM " + schema + ".patient_allergies WHERE reaction_severity = 'UNKNOWN'",
            Integer.class));
    }

    @Test
    void medicalAlertPreventsPatientDeletion()
    {
        String schema = tenant("latest");
        UUID id = patient(schema, "P001");
        jdbc.update(
            "INSERT INTO " + schema + ".patient_medical_alerts "
                + "(id, patient_id, alert_type, alert_description, severity, effective_from, created_at, created_by) "
                + "VALUES (?, ?, 'PRECAUTION', 'Fictional alert', 'WARNING', now(), now(), ?)",
            UUID.randomUUID(), id, UUID.randomUUID());
        assertDatabaseError("23001", "patient_medical_alerts_patient_id_fkey",
            () -> jdbc.update(
                "DELETE FROM " + schema + ".patients WHERE id = ?", id));
    }

    @Test
    void archivalPreservesPatientAndMedicalSafetyRecordsWithoutReleasingPhone()
    {
        String schema = tenant("latest");
        UUID id = patient(schema, "P001");
        allergy(schema, id);
        contact(schema, id, "PHONE", PHONE, true);
        jdbc.update("UPDATE " + schema + ".patients SET archived_at = now(), updated_at = now() WHERE id = ?", id);
        assertEquals(1, jdbc.queryForObject(
            "SELECT count(*) FROM " + schema + ".patients WHERE archived_at IS NOT NULL",
            Integer.class));
        assertEquals(1, jdbc.queryForObject(
            "SELECT count(*) FROM " + schema + ".patient_allergies", Integer.class));
        UUID second = patient(schema, "P002");
        assertDatabaseError("23505", "idx_patient_contacts_unique_normalized_phone",
            () -> contact(schema, second, "PHONE", PHONE, true));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void v6DuplicateDiagnosticsContainCountsOnlyIncludingSamePatientRows(boolean samePatient) throws Exception
    {
        String schema = tenant("5");
        UUID first = patient(schema, "P001");
        UUID second = samePatient ? first : patient(schema, "P002");
        contact(schema, first, "PHONE", PHONE, true);
        contact(schema, second, "MOBILE", PHONE, true);
        String migration = new String(
            getClass().getResourceAsStream(
                "/db/migration/tenant/V6__enforce_unique_patient_phone_numbers.sql").readAllBytes(),
            java.nio.charset.StandardCharsets.UTF_8);
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement())
        {
            statement.execute("SET search_path TO " + schema);
            java.sql.SQLException error = assertThrows(java.sql.SQLException.class, () -> statement.execute(migration));
            assertEquals("P0001", error.getSQLState());
            assertFalse(error.getMessage().contains(PHONE),
                "Migration exception must not contain contact values");
            StringBuilder notices = new StringBuilder();
            for (java.sql.SQLWarning warning = statement.getWarnings(); warning != null;
                    warning = warning.getNextWarning())
            {
                notices.append(warning.getMessage());
            }
            assertFalse(notices.toString().contains(PHONE), "Migration notices must contain counts only");
            assertTrue(notices.toString().contains("duplicate_groups=1, duplicate_rows=2"));
        }
        assertEquals(5, history(schema).size());
    }

    @Test
    void upgradingSixVersionsPreservesExistingRowsAndOriginalInput()
    {
        String schema = tenant("6");
        UUID id = patient(schema, "P001");
        contact(schema, id, "PHONE", PHONE, true);
        assertEquals(List.of("1", "2", "3", "4", "5", "6"), history(schema));
        tenantFlyway(schema, "latest").migrate();
        assertEquals(9, history(schema).size());
        assertEquals(1, jdbc.queryForObject(
            "SELECT count(*) FROM " + schema + ".patients WHERE id = ?",
            Integer.class, id));
        assertEquals("original fictional input", jdbc.queryForObject(
            "SELECT value FROM " + schema + ".patient_contacts",
            String.class));
    }

    @Test
    void patientCreationUpgradePreservesRowsAndStartsNumbersAfterExistingPatientNumbers()
    {
        String schema = tenant("8");
        UUID id = patient(schema, "PAT-000042");
        patient(schema, "legacy-number");
        List<String> originalHistory = history(schema);
        tenantFlyway(schema, "latest").migrate();
        assertEquals(originalHistory, history(schema).subList(0, 8));
        assertEquals("9", history(schema).getLast());
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM " + schema + ".patients", Integer.class));
        assertEquals("PAT-000042", jdbc.queryForObject(
            "SELECT patient_number FROM " + schema + ".patients WHERE id = ?", String.class, id));
        assertEquals(0, jdbc.queryForObject(
            "SELECT count(*) FROM " + schema + ".patients WHERE initial_note IS NOT NULL", Integer.class));
        assertEquals(43L, jdbc.queryForObject(
            "SELECT nextval('" + schema + ".patient_number_seq')", Long.class));
        assertEquals(44L, jdbc.queryForObject(
            "SELECT nextval('" + schema + ".patient_number_seq')", Long.class));
        assertEquals(0, jdbc.queryForObject(
            "SELECT count(*) FROM " + schema + ".patient_contacts", Integer.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "null_lifecycle", "null_phone", "malformed_phone",
        "invalid_birth", "same_patient_duplicate"
    })
    void ambiguousUpgradeFailsWithoutRewritingRows(String scenario)
    {
        String schema = tenant("6");
        UUID id = patient(schema, "P001");
        switch (scenario)
        {
            case "null_lifecycle" -> contact(schema, id, "EMAIL", null, null);
            case "null_phone" -> contact(schema, id, "PHONE", null, true);
            case "malformed_phone" -> contact(schema, id, "PHONE", "unresolved", true);
            case "invalid_birth" -> jdbc.update(
                "UPDATE " + schema + ".patients "
                    + "SET birth_year=2023, birth_month=2, birth_day=31, "
                    + "birth_precision='COMPLETE_DATE' WHERE id=?",
                id);
            case "same_patient_duplicate" ->
            {
                // Simulate index drift without editing any migration; duplicate preflight must still catch both rows.
                jdbc.execute("DROP INDEX " + schema + ".idx_patient_contacts_unique_normalized_phone");
                contact(schema, id, "PHONE", PHONE, true);
                contact(schema, id, "MOBILE", PHONE, true);
            }
            default -> throw new IllegalArgumentException("Unknown fixture");
        }
        Integer contacts = jdbc.queryForObject("SELECT count(*) FROM " + schema + ".patient_contacts", Integer.class);
        FlywayException failure = assertThrows(FlywayException.class, () -> tenantFlyway(schema, "latest").migrate());
        Throwable cause = failure;
        while (cause.getCause() != null)
        {
            cause = cause.getCause();
        }
        assertInstanceOf(java.sql.SQLException.class, cause);
        assertEquals("P0001", ((java.sql.SQLException) cause).getSQLState());
        String counts = switch (scenario)
        {
            case "null_lifecycle" -> "null_lifecycle=1, missing_phone=0, malformed_phone=0,"
                + " duplicate_groups=0, duplicate_rows=0, invalid_birth=0";
            case "null_phone" -> "null_lifecycle=0, missing_phone=1, malformed_phone=0,"
                + " duplicate_groups=0, duplicate_rows=0, invalid_birth=0";
            case "malformed_phone" -> "null_lifecycle=0, missing_phone=0, malformed_phone=1,"
                + " duplicate_groups=0, duplicate_rows=0, invalid_birth=0";
            case "invalid_birth" -> "null_lifecycle=0, missing_phone=0, malformed_phone=0,"
                + " duplicate_groups=0, duplicate_rows=0, invalid_birth=1";
            case "same_patient_duplicate" -> "null_lifecycle=0, missing_phone=0, malformed_phone=0,"
                + " duplicate_groups=1, duplicate_rows=2, invalid_birth=0";
            default -> throw new IllegalArgumentException("Unknown fixture");
        };
        assertTrue(cause.getMessage().contains(counts), "Preflight must report counts for the exact index predicate");
        assertFalse(failure.getMessage().contains(PHONE), "Migration error must not contain contact data");
        assertEquals(6, history(schema).size());
        assertEquals(contacts, jdbc.queryForObject(
            "SELECT count(*) FROM " + schema + ".patient_contacts", Integer.class));
        assertEquals(1, jdbc.queryForObject(
            "SELECT count(*) FROM " + schema + ".patients WHERE id=?",
            Integer.class, id));
    }

    @ParameterizedTest
    @ValueSource(strings = {"wrong_key", "wrong_predicate"})
    void upgradeRejectsPhoneIndexWhoseDefinitionNoLongerProtectsCabinetUniqueness(String scenario)
    {
        String schema = tenant("6");
        jdbc.execute("DROP INDEX " + schema + ".idx_patient_contacts_unique_normalized_phone");
        String keys = scenario.equals("wrong_key") ? "patient_id, value_normalized" : "value_normalized";
        String types = scenario.equals("wrong_predicate") ? "'PHONE'" : "'PHONE', 'MOBILE'";
        jdbc.execute("CREATE UNIQUE INDEX idx_patient_contacts_unique_normalized_phone ON "
            + schema + ".patient_contacts (" + keys + ") WHERE contact_type IN ("
            + types + ") AND is_current = true AND value_normalized IS NOT NULL");

        FlywayException failure = assertThrows(FlywayException.class, () -> tenantFlyway(schema, "latest").migrate());
        Throwable cause = failure;
        while (cause.getCause() != null)
        {
            cause = cause.getCause();
        }
        assertInstanceOf(java.sql.SQLException.class, cause);
        assertEquals("P0001", ((java.sql.SQLException) cause).getSQLState());
        assertTrue(cause.getMessage().contains("required phone uniqueness index definition differs"));
        assertEquals(6, history(schema).size());
    }

    @Test
    void snapshotForeignKeyProtectsSourceMetadata()
    {
        String schema = tenant("latest");
        UUID batch = UUID.randomUUID();
        jdbc.update("INSERT INTO " + schema + ".import_batches "
            + "(id, cabinet_id, source_system_id, batch_label, import_type, started_at, initiated_by) "
            + "VALUES (?, ?, ?, 'Fictional batch', 'PATIENT_IDENTITY', now(), ?)",
            batch, CABINET, UUID.randomUUID(), UUID.randomUUID());
        jdbc.update("INSERT INTO " + schema + ".import_source_snapshots "
            + "(id, batch_id, file_name, file_size_bytes, file_hash, encryption_key_id, "
            + "storage_location, created_at) "
            + "VALUES (?, ?, 'fictional.csv', 1, 'fictional-hash', 'not-real-encryption', "
            + "'fictional-location', now())",
            UUID.randomUUID(), batch);
        assertDatabaseError("23001", "import_source_snapshots_batch_id_fkey",
            () -> jdbc.update("DELETE FROM " + schema + ".import_batches WHERE id=?", batch));
    }

    @Test
    void transactionScopedSchemaSelectionDoesNotLeakOnPhysicalConnectionReuse() throws Exception
    {
        String first = tenant("latest");
        String second = tenant("latest");
        patient(first, "FIRST");
        patient(second, "SECOND");
        int backend;
        try (Connection connection = dataSource.getConnection())
        {
            backend = backend(connection);
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement())
            {
                statement.execute("SET LOCAL search_path TO " + first);
                try (ResultSet rows = statement.executeQuery("SELECT patient_number FROM patients"))
                {
                    assertTrue(rows.next());
                    assertEquals("FIRST", rows.getString(1));
                }
                connection.commit();
            } finally
            {
                connection.rollback();
                connection.setAutoCommit(true);
            }
        }
        try (Connection connection = dataSource.getConnection())
        {
            assertEquals(backend, backend(connection), "Must reuse the same physical PostgreSQL connection");
            assertEquals("pg_catalog", connection.getSchema());
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement())
            {
                statement.execute("SET LOCAL search_path TO " + second);
                try (ResultSet rows = statement.executeQuery("SELECT patient_number FROM patients"))
                {
                    assertTrue(rows.next());
                    assertEquals("SECOND", rows.getString(1));
                }
                java.sql.SQLException error = assertThrows(java.sql.SQLException.class,
                    () -> statement.execute("SELECT 1 / 0"));
                assertEquals("22012", error.getSQLState());
            } finally
            {
                connection.rollback();
                connection.setAutoCommit(true);
            }
        }
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement())
        {
            assertEquals(backend, backend(connection));
            assertEquals("pg_catalog", connection.getSchema());
            java.sql.SQLException error = assertThrows(java.sql.SQLException.class,
                () -> statement.executeQuery("SELECT * FROM patients"));
            assertEquals("42P01", error.getSQLState());
        }
        // JDBC transaction-scoping characterization only: production Hibernate routing does not exist yet.
    }

    private int backend(Connection connection) throws Exception
    {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT pg_backend_pid()"))
        {
            assertTrue(rows.next());
            return rows.getInt(1);
        }
    }
}