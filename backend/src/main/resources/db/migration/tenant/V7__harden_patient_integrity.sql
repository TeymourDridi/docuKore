-- V7: Fail closed on ambiguous existing data; never print identifying values.
-- Flyway executes this migration in one transaction. Schedule a maintenance window:
-- these locks prevent concurrent writes between preflight and constraint validation.
LOCK TABLE patients, patient_contacts, patient_allergies, patient_medical_alerts
    IN ACCESS EXCLUSIVE MODE;

DO $$
DECLARE
    null_lifecycle BIGINT;
    missing_phone BIGINT;
    malformed_phone BIGINT;
    duplicate_groups BIGINT;
    duplicate_rows BIGINT;
    invalid_birth BIGINT;
BEGIN
    SELECT COUNT(*) INTO null_lifecycle FROM patient_contacts WHERE is_current IS NULL;
    SELECT COUNT(*) INTO missing_phone FROM patient_contacts
    WHERE contact_type IN ('PHONE', 'MOBILE') AND is_current = true
      AND value_normalized IS NULL;
    SELECT COUNT(*) INTO malformed_phone FROM patient_contacts
    WHERE contact_type IN ('PHONE', 'MOBILE') AND is_current = true
      AND value_normalized IS NOT NULL
      AND value_normalized !~ '\A\+[1-9][0-9]{1,14}\Z';

    -- Exactly the V6 unique-index predicate; count rows, not distinct patients.
    SELECT COUNT(*), COALESCE(SUM(row_count), 0) INTO duplicate_groups, duplicate_rows
    FROM (
        SELECT COUNT(*) AS row_count FROM patient_contacts
        WHERE contact_type IN ('PHONE', 'MOBILE')
          AND is_current = true AND value_normalized IS NOT NULL
        GROUP BY value_normalized HAVING COUNT(*) > 1
    ) conflicts;

    SELECT COUNT(*) INTO invalid_birth FROM patients
    WHERE (CASE birth_precision
        WHEN 'UNKNOWN' THEN birth_year IS NULL AND birth_month IS NULL AND birth_day IS NULL
        WHEN 'YEAR_ONLY' THEN birth_year > 0 AND birth_month IS NULL AND birth_day IS NULL
        WHEN 'YEAR_MONTH' THEN birth_year > 0 AND birth_month BETWEEN 1 AND 12 AND birth_day IS NULL
        WHEN 'COMPLETE_DATE' THEN birth_year > 0 AND birth_month BETWEEN 1 AND 12
            AND birth_day BETWEEN 1 AND CASE
                WHEN birth_month = 2 THEN CASE
                    WHEN birth_year % 400 = 0 OR (birth_year % 4 = 0 AND birth_year % 100 <> 0) THEN 29
                    ELSE 28 END
                WHEN birth_month IN (4, 6, 9, 11) THEN 30 ELSE 31 END
        ELSE false END) IS NOT TRUE;

    IF null_lifecycle + missing_phone + malformed_phone + duplicate_groups + invalid_birth > 0 THEN
        RAISE EXCEPTION 'V7 preflight failed: null_lifecycle=%, missing_phone=%, malformed_phone=%, duplicate_groups=%, duplicate_rows=%, invalid_birth=%',
            null_lifecycle, missing_phone, malformed_phone, duplicate_groups, duplicate_rows, invalid_birth;
    END IF;

    -- Do not silently accept a missing/invalid concurrency safeguard or repair drift.
    IF NOT EXISTS (
        SELECT 1 FROM pg_index
        WHERE indexrelid = to_regclass('idx_patient_contacts_unique_normalized_phone')
          AND indrelid = 'patient_contacts'::regclass
          AND indisunique AND indisvalid AND indisready
    ) THEN
        RAISE EXCEPTION 'V7 preflight failed: required phone uniqueness index missing or invalid';
    END IF;
    -- A valid unique index with the right name is insufficient: a patient-scoped
    -- key or PHONE-only predicate would still permit cabinet-wide duplicates.
    -- Compare PostgreSQL 18's canonical form of the immutable V6 definition.
    IF NOT EXISTS (
        SELECT 1 FROM pg_index
        WHERE indexrelid = to_regclass('idx_patient_contacts_unique_normalized_phone')
          AND indrelid = 'patient_contacts'::regclass
          AND indnkeyatts = 1 AND indnatts = 1 AND indexprs IS NULL
          AND pg_get_indexdef(indexrelid, 1, true) = 'value_normalized'
          AND pg_get_expr(indpred, indrelid) =
              '(((contact_type)::text = ANY ((ARRAY[''PHONE''::character varying, ''MOBILE''::character varying])::text[])) AND (is_current = true) AND (value_normalized IS NOT NULL))'
    ) THEN
        RAISE EXCEPTION 'V7 preflight failed: required phone uniqueness index definition differs';
    END IF;
END $$;

ALTER TABLE patient_contacts ALTER COLUMN is_current SET NOT NULL;
ALTER TABLE patient_contacts ADD CONSTRAINT patient_contacts_active_phone_e164 CHECK (
    contact_type NOT IN ('PHONE', 'MOBILE') OR is_current = false OR
    (value_normalized IS NOT NULL AND value_normalized ~ '\A\+[1-9][0-9]{1,14}\Z')
);

-- Deterministic calendar validation, including leap centuries. IS TRUE prevents
-- SQL NULL from accepting incomplete precision. Future-date policy belongs in Java.
ALTER TABLE patients DROP CONSTRAINT valid_birth_date;
ALTER TABLE patients ADD CONSTRAINT valid_birth_date CHECK ((CASE birth_precision
    WHEN 'UNKNOWN' THEN birth_year IS NULL AND birth_month IS NULL AND birth_day IS NULL
    WHEN 'YEAR_ONLY' THEN birth_year > 0 AND birth_month IS NULL AND birth_day IS NULL
    WHEN 'YEAR_MONTH' THEN birth_year > 0 AND birth_month BETWEEN 1 AND 12 AND birth_day IS NULL
    WHEN 'COMPLETE_DATE' THEN birth_year > 0 AND birth_month BETWEEN 1 AND 12
        AND birth_day BETWEEN 1 AND CASE
            WHEN birth_month = 2 THEN CASE
                WHEN birth_year % 400 = 0 OR (birth_year % 4 = 0 AND birth_year % 100 <> 0) THEN 29
                ELSE 28 END
            WHEN birth_month IN (4, 6, 9, 11) THEN 30 ELSE 31 END
    ELSE false END) IS TRUE);

ALTER TABLE patients ADD COLUMN archived_at TIMESTAMPTZ;
COMMENT ON COLUMN patients.archived_at IS
    'Soft archive marker; preserves safety records and does not release active phone assignments. Authorization and retention policy are application responsibilities.';

ALTER TABLE patient_allergies DROP CONSTRAINT patient_allergies_patient_id_fkey;
ALTER TABLE patient_allergies ADD CONSTRAINT patient_allergies_patient_id_fkey
    FOREIGN KEY (patient_id) REFERENCES patients(id) ON DELETE RESTRICT;
ALTER TABLE patient_medical_alerts DROP CONSTRAINT patient_medical_alerts_patient_id_fkey;
ALTER TABLE patient_medical_alerts ADD CONSTRAINT patient_medical_alerts_patient_id_fkey
    FOREIGN KEY (patient_id) REFERENCES patients(id) ON DELETE RESTRICT;

ALTER TABLE patient_allergies DROP CONSTRAINT valid_reaction_severity;
ALTER TABLE patient_allergies ADD CONSTRAINT valid_reaction_severity CHECK (
    reaction_severity IN ('UNKNOWN', 'MILD', 'MODERATE', 'SEVERE', 'LIFE_THREATENING')
);
COMMENT ON COLUMN patient_allergies.reaction_severity IS
    'UNKNOWN means a recorded allergy with unknown reaction severity, not no known allergies.';
COMMENT ON CONSTRAINT patient_contacts_active_phone_e164 ON patient_contacts IS
    'E.164-shaped storage only, not country metadata validation. Application normalizes with TN as initial default and preserves original value. Manual patients need not have a phone.';
