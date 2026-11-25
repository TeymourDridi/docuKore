package com.docukore.platform;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/** Read-only catalog checks enforce migration/runtime boundaries before serving requests or activating tenants. */
public final class DatabaseRoleVerifier
{
    /** Fixed application login; roles are infrastructure configuration, never request input. */
    public static final String RUNTIME_ROLE = "docukore_app";

    private DatabaseRoleVerifier()
    {
    }

    /**
     * Check actual login attributes, ownership and effective database privileges.
     *
     * @param connection migration connection
     */
    public static void verifyMigration(Connection connection) throws SQLException
    {
        try (PreparedStatement query = connection.prepareStatement(
            "SELECT current_database(), current_user, m.rolcanlogin "
                + "AND NOT (m.rolsuper OR m.rolcreatedb OR m.rolcreaterole OR m.rolreplication OR m.rolbypassrls) "
                + "AND d.datdba <> m.oid AND has_database_privilege(m.oid, d.oid, 'CONNECT') "
                + "AND has_database_privilege(m.oid, d.oid, 'CREATE') "
                + "AND NOT EXISTS (SELECT 1 FROM pg_catalog.pg_roles x WHERE x.oid <> m.oid "
                + "AND pg_has_role(m.oid, x.oid, 'MEMBER')) "
                + "FROM pg_catalog.pg_roles m JOIN pg_catalog.pg_database d ON d.datname = current_database() "
                + "WHERE m.rolname = current_user"))
        {
            try (ResultSet result = query.executeQuery())
            {
                if (!result.next() || !result.getBoolean(3)
                    || !("docukore_test".equals(result.getString(1))
                        ? "docukore_test_runner" : "docukore_migrator").equals(result.getString(2)))
                {
                    throw new SQLException("DATABASE_MIGRATION_ROLE_INVALID");
                }
            }
        }
    }

    /**
     * Verify the real runtime session rather than relying on a configured role name.
     *
     * @param connection runtime connection
     */
    public static void verifyRuntime(Connection connection) throws SQLException
    {
        try (PreparedStatement query = connection.prepareStatement(
            "SELECT r.rolcanlogin AND NOT (r.rolsuper OR r.rolcreatedb OR r.rolcreaterole "
                + "OR r.rolreplication OR r.rolbypassrls) AND d.datdba <> r.oid "
                + "AND has_database_privilege(r.oid, d.oid, 'CONNECT') "
                + "AND NOT has_database_privilege(r.oid, d.oid, 'CREATE') "
                + "AND NOT has_database_privilege(r.oid, d.oid, 'TEMP') "
                + "AND NOT EXISTS (SELECT 1 FROM pg_catalog.pg_namespace n WHERE n.nspowner = r.oid "
                + "OR (n.nspname NOT LIKE 'pg_%' AND n.nspname <> 'information_schema' "
                + "AND has_schema_privilege(r.oid, n.oid, 'CREATE'))) "
                + "AND NOT EXISTS (SELECT 1 FROM pg_catalog.pg_roles x WHERE x.oid <> r.oid "
                + "AND pg_has_role(r.oid, x.oid, 'MEMBER')) "
                + "FROM pg_catalog.pg_roles r JOIN pg_catalog.pg_database d ON d.datname = current_database() "
                + "WHERE r.rolname = current_user AND r.rolname = ?"))
        {
            query.setString(1, RUNTIME_ROLE);
            requireTrue(query, "DATABASE_RUNTIME_ROLE_INVALID");
        }
    }

    /**
     * Check tenant DML and audit append-only rights on the lock-owning migration session.
     *
     * @param connection migration connection
     * @param schema backend-validated tenant schema
     */
    public static void verifyTenantAccess(Connection connection, String schema) throws SQLException
    {
        try (PreparedStatement query = connection.prepareStatement(
            "SELECT n.nspowner = (SELECT oid FROM pg_catalog.pg_roles WHERE rolname = current_user) "
                + "AND has_schema_privilege(?, n.oid, 'USAGE') AND NOT has_schema_privilege(?, n.oid, 'CREATE') "
                + "AND EXISTS (SELECT 1 FROM pg_catalog.pg_class c WHERE c.relnamespace = n.oid "
                + "AND c.relname = 'audit_logs' AND has_table_privilege(?, c.oid, 'SELECT') "
                + "AND has_table_privilege(?, c.oid, 'INSERT') "
                + "AND NOT has_table_privilege(?, c.oid, 'UPDATE,DELETE,TRUNCATE')) "
                + "AND NOT EXISTS (SELECT 1 FROM pg_catalog.pg_class c WHERE c.relnamespace = n.oid "
                + "AND c.relkind IN ('r', 'p', 'S') AND (c.relowner = "
                + "(SELECT oid FROM pg_catalog.pg_roles WHERE rolname = ?) "
                + "OR CASE WHEN c.relkind = 'S' THEN NOT has_sequence_privilege(?, c.oid, 'USAGE') "
                + "ELSE c.relname = 'patients' "
                + "AND (NOT has_table_privilege(?, c.oid, 'SELECT') "
                + "OR NOT has_table_privilege(?, c.oid, 'INSERT') "
                + "OR NOT has_table_privilege(?, c.oid, 'UPDATE')) END)) "
                + "FROM pg_catalog.pg_namespace n WHERE n.nspname = ?"))
        {
            for (int index = 1; index <= 10; index++)
            {
                query.setString(index, RUNTIME_ROLE);
            }
            query.setString(11, schema);
            requireTrue(query, "DATABASE_TENANT_RUNTIME_GRANTS_MISSING");
        }
    }

    /**
     * Platform login/session writes must be usable before application startup completes.
     *
     * @param connection runtime connection
     * @param schema configured platform schema
     */
    public static void verifyPlatformAccess(Connection connection, String schema) throws SQLException
    {
        try (PreparedStatement query = connection.prepareStatement(
            "SELECT has_schema_privilege(?, n.oid, 'USAGE') AND NOT has_schema_privilege(?, n.oid, 'CREATE') "
                + "AND (SELECT count(*) = 4 FROM pg_catalog.pg_class c WHERE c.relnamespace = n.oid "
                + "AND c.relname IN ('users', 'cabinet_memberships', 'spring_session', 'spring_session_attributes') "
                + "AND has_table_privilege(?, c.oid, 'SELECT') "
                + "AND (c.relname = 'cabinet_memberships' OR (has_table_privilege(?, c.oid, 'INSERT') "
                + "AND has_table_privilege(?, c.oid, 'UPDATE') "
                + "AND (c.relname = 'users' OR has_table_privilege(?, c.oid, 'DELETE'))))) "
                + "FROM pg_catalog.pg_namespace n WHERE n.nspname = ?"))
        {
            for (int index = 1; index <= 6; index++)
            {
                query.setString(index, RUNTIME_ROLE);
            }
            query.setString(7, schema);
            requireTrue(query, "DATABASE_PLATFORM_RUNTIME_GRANTS_MISSING");
        }
    }

    private static void requireTrue(PreparedStatement query, String code) throws SQLException
    {
        try (ResultSet result = query.executeQuery())
        {
            if (!result.next() || !result.getBoolean(1))
            {
                throw new SQLException(code);
            }
        }
    }
}
