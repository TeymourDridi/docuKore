-- V9: Support patient creation without rewriting existing records or migration history.
DO $$
BEGIN
    IF NOT (
        (current_database() = 'docukore_test' AND current_user = 'docukore_test_runner')
        OR (current_database() = 'docukore' AND current_user = 'docukore_migrator')
    ) THEN
        RAISE EXCEPTION 'Invalid migration database and owner role';
    END IF;
    IF '${flyway:defaultSchema}' !~ '^[a-z][a-z0-9_]{0,62}$' THEN
        RAISE EXCEPTION 'Invalid tenant schema identifier';
    END IF;
END;
$$;

-- Nullable for historical rows and omitted input; the application enforces the 10000-character limit.
-- This table lock is retained until Flyway commits, keeping the sequence seed consistent with existing rows.
ALTER TABLE "${flyway:defaultSchema}".patients ADD COLUMN initial_note TEXT;

CREATE SEQUENCE "${flyway:defaultSchema}".patient_number_seq AS BIGINT START WITH 1 NO CYCLE;

DO $$
DECLARE
    next_number NUMERIC;
BEGIN
    -- Ignore legacy formats. NUMERIC avoids overflow while inspecting existing digit-only suffixes.
    SELECT COALESCE(MAX(SUBSTRING(patient_number FROM 5)::NUMERIC), 0) + 1 INTO next_number
    FROM "${flyway:defaultSchema}".patients
    WHERE patient_number ~ '\APAT-[0-9]+\Z';

    IF next_number > 9223372036854775807 THEN
        RAISE EXCEPTION 'Patient number sequence range exhausted';
    END IF;

    -- false means the next nextval returns the seed itself, including 1 for an empty tenant.
    PERFORM setval('"${flyway:defaultSchema}".patient_number_seq'::regclass, next_number::BIGINT, false);
END;
$$;

-- Creation and phone uniqueness conflict checks need only INSERT and SELECT on contacts.
GRANT SELECT, INSERT ON TABLE "${flyway:defaultSchema}".patient_contacts TO docukore_app;

-- Match V8 sequence defaults; runtime may allocate/read numbers, never reset them with setval.
GRANT USAGE, SELECT ON SEQUENCE "${flyway:defaultSchema}".patient_number_seq TO docukore_app;
REVOKE UPDATE ON SEQUENCE "${flyway:defaultSchema}".patient_number_seq FROM PUBLIC, docukore_app;
