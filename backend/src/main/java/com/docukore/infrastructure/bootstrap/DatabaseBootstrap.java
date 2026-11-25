package com.docukore.infrastructure.bootstrap;

import java.io.PrintStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Standalone PostgreSQL administration, without Spring startup or an application datasource.
 * Normal startup and explicit tests supply resolved environment values directly.
 * Passwords never belong in arguments, generated files or diagnostic output.
 */
public final class DatabaseBootstrap
{
    /** Application runtime identity, never a schema or database owner. */
    private static final String RUNTIME_ROLE = "docukore_app";

    /** Production migration/provisioning identity, never a database owner. */
    private static final String MIGRATION_ROLE = "docukore_migrator";

    /** Existing restricted integration-test schema owner. */
    private static final String TEST_ROLE = "docukore_test_runner";

    /** Cluster-wide bootstrap serialization lock, held by the maintenance session. */
    private static final long BOOTSTRAP_LOCK = 74218090742L;

    /** Trusted, validated supplied environment configuration. */
    private final BootstrapConfiguration configuration;

    /** Explicit operation boundary. */
    private final BootstrapMode mode;

    /**
     * Construct a standalone administrator without dependency lookup or a Spring context.
     *
     * @param configuration private validated configuration
     * @param mode explicit operation
     */
    DatabaseBootstrap(BootstrapConfiguration configuration, BootstrapMode mode)
    {
        this.configuration = configuration;
        this.mode = mode;
    }

    /**
     * Initialize only the application database and its runtime/migrator roles before Spring startup.
     *
     * @param environment resolved environment values, never modified
     */
    public static void initialize(Map<String, String> environment)
    {
        initialize(environment, BootstrapMode.DEVELOPMENT);
    }

    /**
     * Initialize only the test database and its runtime/test-runner roles by explicit request.
     *
     * @param environment resolved environment values, never modified
     */
    public static void initializeTest(Map<String, String> environment)
    {
        initialize(environment, BootstrapMode.TEST);
    }

    /**
     * Explicitly recreate both existing disposable development databases after all reset guards pass.
     * Ordinary initialization never invokes this operation.
     *
     * @param environment resolved environment values including the explicit development reset category
     */
    public static void resetDevelopment(Map<String, String> environment)
    {
        initialize(environment, BootstrapMode.RESET_DEVELOPMENT);
    }

    /**
     * Sanitize every failure before returning to a caller; no SQL, credential, message or cause escapes.
     *
     * @param environment resolved environment values
     * @param mode explicitly selected operation
     */
    private static void initialize(Map<String, String> environment, BootstrapMode mode)
    {
        try
        {
            BootstrapConfiguration configuration = BootstrapConfiguration.fromEnvironment(environment, mode);
            new DatabaseBootstrap(configuration, mode).execute();
        } catch (BootstrapFailure failure)
        {
            throw failure;
        } catch (SQLException failure)
        {
            throw new BootstrapFailure("BOOTSTRAP_DATABASE_FAILED");
        } catch (RuntimeException | LinkageError failure)
        {
            throw new BootstrapFailure("BOOTSTRAP_FAILED");
        }
    }

    /**
     * Print only fixed outcome codes at the standalone process boundary.
     *
     * @param arguments selected mode
     * @param environment resolved environment values
     * @param successOutput fixed success output sink
     * @param failureOutput fixed diagnostic output sink
     * @return process status
     */
    static int run(String[] arguments, Map<String, String> environment, PrintStream successOutput,
        PrintStream failureOutput)
    {
        try
        {
            initialize(environment, BootstrapMode.parse(arguments));
            successOutput.println("BOOTSTRAP_COMPLETED");
            return 0;
        } catch (BootstrapFailure failure)
        {
            failureOutput.println(failure.getMessage());
            return 1;
        }
    }

    /**
     * Separate maintenance autocommit from transaction-scoped schema ACL changes.
     *
     * @throws SQLException when PostgreSQL rejects an operation
     * */
    private void execute() throws SQLException
    {
        configuration.validateMode(mode);
        List<Target> targets = targets();
        try (Connection maintenance = connect("postgres", configuration.bootstrapUsername(),
            configuration.bootstrapPassword()))
        {
            maintenance.setAutoCommit(true);
            verifyAuthority(maintenance, configuration.bootstrapUsername());
            verifyServer(maintenance);
            silenceSensitiveSql(maintenance);
            acquireLock(maintenance);
            preflightDatabases(maintenance, targets);
            Map<String, String> passwords = preparePasswords(maintenance, targets);
            verifyExistingPasswords(maintenance, targets, passwords);
            createAbsentRoles(maintenance, targets, passwords);
            if (mode == BootstrapMode.RESET_DEVELOPMENT)
            {
                resetDatabases(maintenance, targets);
            }
            for (Target target : targets)
            {
                boolean fresh = createDatabaseIfAbsent(maintenance, target);
                configureDatabase(target, fresh);
                verifyCredentials(target.database(), target.writer(), passwords.get(target.writer()));
                verifyCredentials(target.database(), RUNTIME_ROLE, passwords.get(RUNTIME_ROLE));
            }
        }
    }

    /**
     * Use JDBC auto-registration; neither Class.forName nor Boot startup is needed.
     *
     * @param database fixed database
     * @param username fixed role
     * @param password protected credential
     * @return dedicated physical connection
     * @throws SQLException when authentication or connection fails
     */
    private Connection connect(String database, String username, String password) throws SQLException
    {
        Properties properties = new Properties();
        properties.setProperty("user", username);
        properties.setProperty("password", password);
        properties.setProperty("connectTimeout", "10");
        properties.setProperty("socketTimeout", "30");
        properties.setProperty("ApplicationName", "docukore-bootstrap");
        return DriverManager.getConnection(configuration.url(database), properties);
    }

    /**
     * Verify the immutable bootstrap role and the actual maintenance target, not configured claims.
     *
     * @param connection maintenance connection
     * @param administrator configured existing administrator identity
     * @throws SQLException when catalog inspection fails
     */
    static void verifyAuthority(Connection connection, String administrator) throws SQLException
    {
        String sql = "SELECT r.oid, r.rolsuper, r.rolname, session_user, current_database() "
            + "FROM pg_catalog.pg_roles r WHERE r.rolname = current_user";
        try (Statement statement = connection.createStatement(); ResultSet row = statement.executeQuery(sql))
        {
            if (!row.next() || !row.getBoolean(2)
                || !administrator.equals(row.getString(3)) || !administrator.equals(row.getString(4))
                || !"postgres".equals(row.getString(5)))
            {
                throw new BootstrapFailure("BOOTSTRAP_AUTHORITY_INVALID");
            }
        }
    }

    /**
     * Ordinary bootstrap validates the configured PostgreSQL 18 policy; reset requires exactly 18.7.
     *
     * @param connection maintenance connection
     * @throws SQLException when server inspection fails
     */
    private void verifyServer(Connection connection) throws SQLException
    {
        try (Statement statement = connection.createStatement();
            ResultSet row = statement.executeQuery("SELECT current_setting('server_version_num')::integer"))
        {
            if (!row.next())
            {
                throw new BootstrapFailure("BOOTSTRAP_SERVER_VERSION_INVALID");
            }
            int actual = row.getInt(1);
            String expected = configuration.expectedVersion();
            int required = expected.contains(".") ? 180000 + Integer.parseInt(expected.substring(3)) : 180000;
            if (actual / 10000 != 18 || (expected.contains(".") && actual != required)
                || (mode == BootstrapMode.RESET_DEVELOPMENT && actual != 180007))
            {
                throw new BootstrapFailure("BOOTSTRAP_SERVER_VERSION_INVALID");
            }
        }
    }

    /**
     * Suppress built-in PostgreSQL statement/error sampling before password-bearing role DDL.
     * External auditing extensions remain an operator responsibility.
     *
     * @param connection bootstrap session
     * @throws SQLException when safe session setup fails
     */
    private static void silenceSensitiveSql(Connection connection) throws SQLException
    {
        executeSql(connection, "SET log_statement = 'none'");
        executeSql(connection, "SET log_min_error_statement = 'panic'");
        executeSql(connection, "SET log_min_duration_statement = -1");
        executeSql(connection, "SET log_min_duration_sample = -1");
        executeSql(connection, "SET log_transaction_sample_rate = 0");
        executeSql(connection, "SET log_duration = off");
        executeSql(connection, "SET password_encryption = 'scram-sha-256'");
    }

    /**
     * Fail immediately instead of waiting behind another bootstrap or reset process.
     *
     * @param connection maintenance connection
     * @throws SQLException when lock acquisition fails
     */
    private static void acquireLock(Connection connection) throws SQLException
    {
        try (PreparedStatement statement = connection.prepareStatement("SELECT pg_try_advisory_lock(?)"))
        {
            statement.setLong(1, BOOTSTRAP_LOCK);
            try (ResultSet row = statement.executeQuery())
            {
                if (!row.next() || !row.getBoolean(1))
                {
                    throw new BootstrapFailure("BOOTSTRAP_ALREADY_RUNNING");
                }
            }
        }
    }

    /**
     * Inspect every selected database before changes; reset never creates an absent reset target.
     *
     * @param maintenance maintenance connection
     * @param targets fixed selected targets
     * @throws SQLException when inspection fails
     */
    private void preflightDatabases(Connection maintenance, List<Target> targets) throws SQLException
    {
        for (Target target : targets)
        {
            boolean exists = databaseExists(maintenance, target.database());
            if (!exists && mode == BootstrapMode.RESET_DEVELOPMENT)
            {
                throw new BootstrapFailure("BOOTSTRAP_RESET_DATABASE_MISSING");
            }
            if (exists)
            {
                verifyDatabaseOwner(maintenance, target.database());
                if (mode != BootstrapMode.RESET_DEVELOPMENT)
                {
                    try (Connection database = connect(target.database(), configuration.bootstrapUsername(),
                        configuration.bootstrapPassword()))
                    {
                        verifyExistingOwnership(database, target.writer());
                    }
                }
            }
        }
    }

    /**
     * Preflight role safety before any role/database creation, preserving every existing password.
     *
     * @param maintenance maintenance connection
     * @param targets selected targets
     * @return selected role credentials
     * @throws SQLException when role inspection fails
     */
    private Map<String, String> preparePasswords(Connection maintenance, List<Target> targets)
        throws SQLException
    {
        List<String> roles = new ArrayList<>();
        roles.add(RUNTIME_ROLE);
        for (Target target : targets)
        {
            roles.add(target.writer());
        }
        Map<String, String> passwords = new LinkedHashMap<>();
        for (String role : roles)
        {
            if (roleExists(maintenance, role))
            {
                verifyRestrictedRole(maintenance, role);
            }
            passwords.put(role, configuration.rolePassword(role));
        }
        return passwords;
    }

    /**
     * Authenticate all existing selected credentials before changes; no ALTER ROLE PASSWORD fallback exists.
     *
     * @param maintenance maintenance connection
     * @param targets selected databases
     * @param passwords supplied credentials
     * @throws SQLException when catalog inspection fails
     */
    private void verifyExistingPasswords(Connection maintenance, List<Target> targets, Map<String, String> passwords)
        throws SQLException
    {
        for (String role : passwords.keySet())
        {
            if (!roleExists(maintenance, role))
            {
                continue;
            }
            String database = "postgres";
            for (Target target : targets)
            {
                if (databaseExists(maintenance, target.database())
                    && hasConnect(maintenance, role, target.database()))
                {
                    database = target.database();
                    break;
                }
            }
            verifyCredentials(database, role, passwords.get(role));
        }
    }

    /**
     * Create absent roles only; never change flags/passwords or drop any existing role.
     *
     * @param maintenance maintenance connection
     * @param targets selected databases
     * @param passwords selected credentials
     * @throws SQLException when creation fails
     */
    private static void createAbsentRoles(Connection maintenance, List<Target> targets, Map<String, String> passwords)
        throws SQLException
    {
        List<String> roles = new ArrayList<>();
        roles.add(RUNTIME_ROLE);
        for (Target target : targets)
        {
            roles.add(target.writer());
        }
        for (String role : roles)
        {
            if (!roleExists(maintenance, role))
            {
                executeSql(maintenance, createRoleSql(role, passwords.get(role)));
            }
            verifyRestrictedRole(maintenance, role);
        }
    }

    /**
     * Verify restricted flags, no membership inheritance/SET ROLE and no database ownership.
     *
     * @param connection maintenance connection
     * @param role fixed role name
     * @throws SQLException when catalog inspection fails
     */
    static void verifyRestrictedRole(Connection connection, String role) throws SQLException
    {
        String sql = "SELECT r.rolcanlogin, r.rolsuper, r.rolcreatedb, r.rolcreaterole, "
            + "r.rolreplication, r.rolbypassrls, "
            + "EXISTS (SELECT 1 FROM pg_catalog.pg_auth_members m WHERE m.member = r.oid), "
            + "EXISTS (SELECT 1 FROM pg_catalog.pg_database d WHERE d.datdba = r.oid) "
            + "FROM pg_catalog.pg_roles r WHERE r.rolname = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql))
        {
            statement.setString(1, role);
            try (ResultSet row = statement.executeQuery())
            {
                if (!row.next() || !row.getBoolean(1) || row.getBoolean(2) || row.getBoolean(3)
                    || row.getBoolean(4) || row.getBoolean(5) || row.getBoolean(6) || row.getBoolean(7)
                    || row.getBoolean(8))
                {
                    throw new BootstrapFailure("BOOTSTRAP_ROLE_UNSAFE");
                }
            }
        }
    }

    /**
     * Password syntax cannot bind parameters in CREATE ROLE; escape both quotes and backslashes explicitly.
     *
     * @param role fixed allowlisted role
     * @param password private single-line password
     * @return SQL for direct execution only; never log or attach to exceptions
     */
    static String createRoleSql(String role, String password)
    {
        if (!List.of(RUNTIME_ROLE, MIGRATION_ROLE, TEST_ROLE).contains(role))
        {
            throw new BootstrapFailure("BOOTSTRAP_ROLE_INVALID");
        }
        BootstrapConfiguration.validatePassword(password);
        String escaped = password.replace("\\", "\\\\").replace("'", "''");
        return "CREATE ROLE " + role + " LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS "
            + "PASSWORD E'" + escaped + "'";
    }

    /**
     * Drop only both existing, verified disposable database names, on the maintenance autocommit connection.
     * Roles, credentials, volumes and all other databases remain unchanged.
     *
     * @param maintenance verified maintenance connection
     * @param targets fixed reset targets
     * @throws SQLException when PostgreSQL refuses the reset
     */
    private void resetDatabases(Connection maintenance, List<Target> targets) throws SQLException
    {
        configuration.validateMode(mode);
        verifyAuthority(maintenance, configuration.bootstrapUsername());
        verifyServer(maintenance);
        if (mode != BootstrapMode.RESET_DEVELOPMENT || targets.size() != 2 || !maintenance.getAutoCommit())
        {
            throw new BootstrapFailure("BOOTSTRAP_RESET_TARGET_INVALID");
        }
        for (Target target : targets)
        {
            verifyDatabaseOwner(maintenance, target.database());
        }
        for (Target target : targets)
        {
            executeSql(maintenance, "DROP DATABASE " + target.database() + " WITH (FORCE)");
        }
    }

    /**
     * Database creation is autocommit and always retains the bootstrap database owner.
     *
     * @param maintenance maintenance connection
     * @param target fixed database
     * @return whether the database was just created
     * @throws SQLException when creation fails
     */
    private boolean createDatabaseIfAbsent(Connection maintenance, Target target) throws SQLException
    {
        if (databaseExists(maintenance, target.database()))
        {
            verifyDatabaseOwner(maintenance, target.database());
            return false;
        }
        if (!maintenance.getAutoCommit())
        {
            throw new BootstrapFailure("BOOTSTRAP_AUTOCOMMIT_REQUIRED");
        }
        executeSql(maintenance, "CREATE DATABASE " + target.database() + " OWNER "
            + quoteIdentifier(configuration.bootstrapUsername())
            + " TEMPLATE template0 ENCODING 'UTF8'");
        verifyDatabaseOwner(maintenance, target.database());
        return true;
    }

    /**
     * Configure database/schema privileges only. Flyway owns all object and default DML grants.
     * Existing object ownership is never reassigned; public ownership changes only in a fresh database.
     *
     * @param target selected fixed database
     * @param fresh whether bootstrap created the empty database
     * @throws SQLException when setup fails
     */
    private void configureDatabase(Target target, boolean fresh) throws SQLException
    {
        try (Connection database = connect(target.database(), configuration.bootstrapUsername(),
            configuration.bootstrapPassword()))
        {
            database.setAutoCommit(false);
            try
            {
                verifyExistingOwnership(database, target.writer());
                executeSql(database, "REVOKE ALL ON DATABASE " + target.database() + " FROM PUBLIC");
                executeSql(database,
                    "REVOKE CREATE, TEMPORARY ON DATABASE " + target.database() + " FROM " + RUNTIME_ROLE);
                executeSql(database, "GRANT CONNECT ON DATABASE " + target.database() + " TO " + RUNTIME_ROLE);
                executeSql(database,
                    "GRANT CONNECT, CREATE ON DATABASE " + target.database() + " TO " + target.writer());
                executeSql(database, "REVOKE CREATE ON SCHEMA public FROM PUBLIC");
                executeSql(database, "REVOKE CREATE ON SCHEMA public FROM " + RUNTIME_ROLE);
                executeSql(database, "GRANT USAGE ON SCHEMA public TO " + RUNTIME_ROLE);
                executeSql(database, "GRANT USAGE, CREATE ON SCHEMA public TO " + target.writer());
                if (fresh)
                {
                    executeSql(database, "ALTER SCHEMA public OWNER TO " + target.writer());
                }
                verifyRuntimeBoundary(database, target.database());
                database.commit();
            } catch (SQLException | RuntimeException failure)
            {
                database.rollback();
                throw failure;
            }
        }
    }

    /**
     * Reject silent legacy adoption and unexpected public ownership before ordinary setup.
     *
     * @param connection target connection
     * @param writer fixed migration/test role
     * @throws SQLException when ownership inspection fails
     */
    private void verifyExistingOwnership(Connection connection, String writer) throws SQLException
    {
        String schema = "SELECT pg_catalog.pg_get_userbyid(nspowner) FROM pg_catalog.pg_namespace "
            + "WHERE nspname = 'public'";
        try (Statement statement = connection.createStatement(); ResultSet row = statement.executeQuery(schema))
        {
            if (!row.next() || !List.of("pg_database_owner", configuration.bootstrapUsername(), writer)
                .contains(row.getString(1)))
            {
                throw new BootstrapFailure("BOOTSTRAP_SCHEMA_OWNER_INVALID");
            }
        }
        String sql = "SELECT EXISTS (SELECT 1 FROM pg_catalog.pg_class c "
            + "JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace "
            + "WHERE n.nspname !~ '^pg_' AND n.nspname <> 'information_schema' "
            + "AND pg_catalog.pg_get_userbyid(c.relowner) <> ?) "
            + "OR EXISTS (SELECT 1 FROM pg_catalog.pg_proc p "
            + "JOIN pg_catalog.pg_namespace n ON n.oid = p.pronamespace "
            + "WHERE n.nspname !~ '^pg_' AND n.nspname <> 'information_schema' "
            + "AND pg_catalog.pg_get_userbyid(p.proowner) <> ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql))
        {
            statement.setString(1, writer);
            statement.setString(2, writer);
            try (ResultSet row = statement.executeQuery())
            {
                if (!row.next() || row.getBoolean(1))
                {
                    throw new BootstrapFailure("BOOTSTRAP_LEGACY_OWNERSHIP_REQUIRES_EXPLICIT_RESET");
                }
            }
        }
    }

    /**
     * Runtime cannot create schemas, persistent or temporary tables, own objects or SET ROLE to a writer.
     *
     * @param connection target connection
     * @param database fixed database name
     * @throws SQLException when privilege verification fails
     */
    private static void verifyRuntimeBoundary(Connection connection, String database) throws SQLException
    {
        String sql = "SELECT has_database_privilege(?, ?, 'CONNECT'), has_database_privilege(?, ?, 'CREATE'), "
            + "has_database_privilege(?, ?, 'TEMPORARY'), EXISTS (SELECT 1 FROM pg_catalog.pg_namespace n "
            + "WHERE n.nspname !~ '^pg_' AND n.nspname <> 'information_schema' "
            + "AND (has_schema_privilege(?, n.oid, 'CREATE') OR pg_catalog.pg_get_userbyid(n.nspowner) = ?))";
        try (PreparedStatement statement = connection.prepareStatement(sql))
        {
            for (int index = 1; index <= 6; index += 2)
            {
                statement.setString(index, RUNTIME_ROLE);
                statement.setString(index + 1, database);
            }
            statement.setString(7, RUNTIME_ROLE);
            statement.setString(8, RUNTIME_ROLE);
            try (ResultSet row = statement.executeQuery())
            {
                if (!row.next() || !row.getBoolean(1) || row.getBoolean(2) || row.getBoolean(3) || row.getBoolean(4))
                {
                    throw new BootstrapFailure("BOOTSTRAP_RUNTIME_DDL_UNSAFE");
                }
            }
        }
    }

    /**
     * Check database existence using bound catalog lookup, not constructed identifiers.
     *
     * @param connection maintenance connection
     * @param database fixed database name
     * @return whether it exists
     * @throws SQLException when lookup fails
     */
    private static boolean databaseExists(Connection connection, String database) throws SQLException
    {
        return exists(connection, "SELECT 1 FROM pg_catalog.pg_database WHERE datname = ?", database);
    }

    /**
     * Check role existence without querying password hashes.
     *
     * @param connection maintenance connection
     * @param role fixed role
     * @return whether it exists
     * @throws SQLException when lookup fails
     */
    private static boolean roleExists(Connection connection, String role) throws SQLException
    {
        return exists(connection, "SELECT 1 FROM pg_catalog.pg_roles WHERE rolname = ?", role);
    }

    /**
     * Perform one bound catalog existence query.
     *
     * @param connection catalog connection
     * @param sql fixed SQL
     * @param value fixed role/database
     * @return whether a row exists
     * @throws SQLException when query fails
     */
    private static boolean exists(Connection connection, String sql, String value) throws SQLException
    {
        try (PreparedStatement statement = connection.prepareStatement(sql))
        {
            statement.setString(1, value);
            try (ResultSet row = statement.executeQuery())
            {
                return row.next();
            }
        }
    }

    /**
     * Require OID-10 database ownership and usable nontemplate targets.
     *
     * @param connection maintenance connection
     * @param database fixed database name
     * @throws SQLException when inspection fails
     */
    private void verifyDatabaseOwner(Connection connection, String database) throws SQLException
    {
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT pg_catalog.pg_get_userbyid(datdba), datistemplate, datallowconn "
                + "FROM pg_catalog.pg_database WHERE datname = ?"))
        {
            statement.setString(1, database);
            try (ResultSet row = statement.executeQuery())
            {
                if (!row.next() || !configuration.bootstrapUsername().equals(row.getString(1))
                    || row.getBoolean(2) || !row.getBoolean(3))
                {
                    throw new BootstrapFailure("BOOTSTRAP_DATABASE_OWNER_INVALID");
                }
            }
        }
    }

    /**
     * Select an existing accessible target for credential verification without changing passwords.
     *
     * @param connection maintenance connection
     * @param role fixed role
     * @param database fixed database
     * @return whether CONNECT is granted
     * @throws SQLException when inspection fails
     */
    private static boolean hasConnect(Connection connection, String role, String database) throws SQLException
    {
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT has_database_privilege(?, ?, 'CONNECT')"))
        {
            statement.setString(1, role);
            statement.setString(2, database);
            try (ResultSet row = statement.executeQuery())
            {
                return row.next() && row.getBoolean(1);
            }
        }
    }

    /**
     * Authenticate the actual supplied credential, refusing to silently reset an existing login.
     *
     * @param database fixed database
     * @param role fixed role
     * @param password supplied credential
     */
    private void verifyCredentials(String database, String role, String password)
    {
        try (Connection authenticated = connect(database, role, password);
            Statement statement = authenticated.createStatement();
            ResultSet row = statement.executeQuery("SELECT session_user, current_user, current_database()"))
        {
            if (!row.next() || !role.equals(row.getString(1)) || !role.equals(row.getString(2))
                || !database.equals(row.getString(3)))
            {
                throw new BootstrapFailure("BOOTSTRAP_CREDENTIAL_VERIFICATION_FAILED");
            }
        } catch (SQLException failure)
        {
            throw new BootstrapFailure("BOOTSTRAP_CREDENTIAL_VERIFICATION_FAILED");
        }
    }

    /**
     * Determine immutable internal target identifiers; no file/CLI database or schema names are trusted.
     *
     * @return fixed selected databases
     */
    private List<Target> targets()
    {
        Target development = new Target("docukore", MIGRATION_ROLE, "migration.password");
        Target test = new Target("docukore_test", TEST_ROLE, "test.password");
        return switch (mode)
        {
            case DEVELOPMENT -> List.of(development);
            case TEST -> List.of(test);
            case RESET_DEVELOPMENT -> List.of(development, test);
        };
    }

    /**
     * Quote an administrator identifier that has already passed configuration validation.
     * @param identifier validated administrator name
     * @return quoted SQL identifier
     */
    private static String quoteIdentifier(String identifier)
    {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    /**
     * Execute source-controlled SQL only; never expose the statement in diagnostics.
     *
     * @param connection dedicated bootstrap connection
     * @param sql fixed SQL with allowlisted identifiers and explicitly escaped password literals
     * @throws SQLException when execution fails
     */
    private static void executeSql(Connection connection, String sql) throws SQLException
    {
        try (Statement statement = connection.createStatement())
        {
            statement.execute(sql);
        }
    }

    /**
     * Fixed internal target without any credentials.
     *
     * @param database fixed database identifier
     * @param writer fixed schema-creation role
     * @param passwordKey fixed credential property key
     */
    private record Target(String database, String writer, String passwordKey)
    {
    }
}
