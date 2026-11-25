-- Tenant schema names are resolved and validated by the backend, never supplied by a client.
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

REVOKE CREATE ON SCHEMA "${flyway:defaultSchema}" FROM PUBLIC, docukore_app;
GRANT USAGE ON SCHEMA "${flyway:defaultSchema}" TO docukore_app;

-- Patient persistence is the only current clinical/business runtime table.
-- Existing identity/contact/staff/import tables get no speculative feature grants.
GRANT SELECT, INSERT, UPDATE ON TABLE "${flyway:defaultSchema}".patients TO docukore_app;
REVOKE DELETE, TRUNCATE ON TABLE "${flyway:defaultSchema}".patients FROM PUBLIC, docukore_app;

-- Audit history is append-only, even if an earlier ACL was broader.
GRANT SELECT, INSERT ON TABLE "${flyway:defaultSchema}".audit_logs TO docukore_app;
REVOKE UPDATE, DELETE, TRUNCATE ON TABLE "${flyway:defaultSchema}".audit_logs FROM PUBLIC, docukore_app;

GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA "${flyway:defaultSchema}" TO docukore_app;
REVOKE UPDATE ON ALL SEQUENCES IN SCHEMA "${flyway:defaultSchema}" FROM PUBLIC, docukore_app;

-- Migration history is not a runtime object.
REVOKE SELECT, INSERT, UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER ON TABLE
    "${flyway:defaultSchema}".flyway_schema_history_tenant
FROM PUBLIC, docukore_app;

-- CURRENT_USER is the effective owner (docukore_migrator or docukore_test_runner).
-- Per-schema defaults apply only to its future objects and do not transfer ownership.
-- A future append-only table must explicitly narrow these general DML defaults, like audit_logs above.
ALTER DEFAULT PRIVILEGES IN SCHEMA "${flyway:defaultSchema}"
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO docukore_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA "${flyway:defaultSchema}"
    REVOKE TRUNCATE, REFERENCES, TRIGGER ON TABLES FROM docukore_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA "${flyway:defaultSchema}"
    GRANT USAGE, SELECT ON SEQUENCES TO docukore_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA "${flyway:defaultSchema}"
    REVOKE UPDATE ON SEQUENCES FROM docukore_app;
