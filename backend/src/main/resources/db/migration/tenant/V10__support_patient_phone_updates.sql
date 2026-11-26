-- V10: Permit retiring current phone rows while retaining their values and contact history.
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

-- V9 already grants SELECT/INSERT; replacement inserts a new PHONE rather than rewriting old values.
GRANT UPDATE (is_current) ON TABLE "${flyway:defaultSchema}".patient_contacts TO docukore_app;
