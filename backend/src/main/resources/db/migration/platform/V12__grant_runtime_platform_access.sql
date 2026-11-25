-- The backend/Flyway configuration validates this identifier before placeholder replacement.
-- public in production; an invocation-owned isolated platform schema in PostgreSQL tests.
DO $$
BEGIN
    IF NOT (
        (current_database() = 'docukore_test' AND current_user = 'docukore_test_runner')
        OR (current_database() = 'docukore' AND current_user = 'docukore_migrator')
    ) THEN
        RAISE EXCEPTION 'Invalid migration database and owner role';
    END IF;
    IF '${flyway:defaultSchema}' !~ '^[a-z][a-z0-9_]{0,62}$' THEN
        RAISE EXCEPTION 'Invalid platform schema identifier';
    END IF;
END;
$$;

REVOKE CREATE ON SCHEMA "${flyway:defaultSchema}" FROM PUBLIC, docukore_app;
GRANT USAGE ON SCHEMA "${flyway:defaultSchema}" TO docukore_app;

-- Current runtime queries, not provisioning/migration writes or future feature APIs.
GRANT SELECT, INSERT, UPDATE ON TABLE
    "${flyway:defaultSchema}".users,
    "${flyway:defaultSchema}".initial_cabinet_registrations,
    "${flyway:defaultSchema}".email_verification_tokens
TO docukore_app;
GRANT SELECT, UPDATE ON TABLE "${flyway:defaultSchema}".installation_setup TO docukore_app;
GRANT SELECT, INSERT ON TABLE
    "${flyway:defaultSchema}".cabinets,
    "${flyway:defaultSchema}".cabinet_schema_allocations
TO docukore_app;
GRANT SELECT ON TABLE
    "${flyway:defaultSchema}".cabinet_memberships,
    "${flyway:defaultSchema}".membership_permissions,
    "${flyway:defaultSchema}".cabinet_subscriptions,
    "${flyway:defaultSchema}".cabinet_legacy_schema_observations,
    "${flyway:defaultSchema}".cabinet_schema_creation_receipts
TO docukore_app;

-- Spring Session reads, creates, updates and expires sessions and their attributes.
GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE
    "${flyway:defaultSchema}".spring_session,
    "${flyway:defaultSchema}".spring_session_attributes
TO docukore_app;

GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA "${flyway:defaultSchema}" TO docukore_app;
REVOKE UPDATE ON ALL SEQUENCES IN SCHEMA "${flyway:defaultSchema}" FROM PUBLIC, docukore_app;

-- Receipt creation and legacy admission belong exclusively to the migrator/provisioner.
-- Retain the existing immutable triggers as a second boundary, not a substitute for ACLs.
REVOKE UPDATE, DELETE, TRUNCATE ON TABLE
    "${flyway:defaultSchema}".cabinet_schema_allocations
FROM PUBLIC, docukore_app;
REVOKE DELETE, TRUNCATE ON TABLE
    "${flyway:defaultSchema}".initial_cabinet_registrations
FROM PUBLIC, docukore_app;
REVOKE INSERT, UPDATE, DELETE, TRUNCATE ON TABLE
    "${flyway:defaultSchema}".cabinet_schema_creation_receipts,
    "${flyway:defaultSchema}".cabinet_legacy_schema_observations,
    "${flyway:defaultSchema}".cabinet_subscriptions
FROM PUBLIC, docukore_app;

-- Runtime never runs Flyway, including reading or altering its history.
REVOKE SELECT, INSERT, UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER ON TABLE
    "${flyway:defaultSchema}".flyway_schema_history
FROM PUBLIC, docukore_app;

-- Defaults affect only future objects created by CURRENT_USER, the effective migration owner:
-- docukore_migrator in development/production, docukore_test_runner in isolated tests.
-- They neither transfer ownership nor grant CREATE. Future immutable objects must narrow their ACLs.
ALTER DEFAULT PRIVILEGES IN SCHEMA "${flyway:defaultSchema}"
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO docukore_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA "${flyway:defaultSchema}"
    REVOKE TRUNCATE, REFERENCES, TRIGGER ON TABLES FROM docukore_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA "${flyway:defaultSchema}"
    GRANT USAGE, SELECT ON SEQUENCES TO docukore_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA "${flyway:defaultSchema}"
    REVOKE UPDATE ON SEQUENCES FROM docukore_app;
