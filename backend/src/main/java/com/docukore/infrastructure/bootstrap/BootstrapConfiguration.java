package com.docukore.infrastructure.bootstrap;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Validated, supplied environment values without file access or generated credentials. */
final class BootstrapConfiguration
{
    /** Validated single-server maintenance endpoint. */
    private final URI endpoint;

    /** Existing administrator identity, never created or altered by bootstrap. */
    private final String bootstrapUsername;

    /** Supplied administrator credential, excluded from generated object representations. */
    private final String bootstrapPassword;

    /** Explicit server-version policy. */
    private final String expectedVersion;

    /** Deployment category used only to authorize explicit development reset. */
    private final String environment;

    /** Selected-mode role credentials only. */
    private final Map<String, String> passwords;

    /**
     * Construct validated, immutable configuration without retaining unrelated environment values.
     *
     * @param endpoint maintenance endpoint
     * @param bootstrapUsername existing administrator
     * @param bootstrapPassword supplied administrator credential
     * @param expectedVersion server-version policy
     * @param environment explicit reset deployment category
     * @param passwords selected restricted-role credentials
     */
    private BootstrapConfiguration(URI endpoint, String bootstrapUsername, String bootstrapPassword,
        String expectedVersion, String environment, Map<String, String> passwords)
    {
        this.endpoint = endpoint;
        this.bootstrapUsername = bootstrapUsername;
        this.bootstrapPassword = bootstrapPassword;
        this.expectedVersion = expectedVersion;
        this.environment = environment;
        this.passwords = Map.copyOf(passwords);
    }

    /**
     * Validate only the supplied values needed by the explicitly selected operation.
     *
     * @param values resolved environment values, never modified
     * @param mode explicit operation boundary
     * @return validated configuration
     */
    static BootstrapConfiguration fromEnvironment(Map<String, String> values, BootstrapMode mode)
    {
        if (values == null || values.isEmpty())
        {
            throw new BootstrapFailure("BOOTSTRAP_CONFIG_REQUIRED");
        }
        if (mode == null)
        {
            throw new BootstrapFailure("BOOTSTRAP_MODE_INVALID");
        }
        URI endpoint = parseEndpoint(required(values, "DOCUKORE_BOOTSTRAP_URL"), "postgres");
        String administrator = required(values, "DOCUKORE_BOOTSTRAP_USERNAME");
        if (!administrator.matches("[A-Za-z_][A-Za-z0-9_]{0,62}")
            || Set.of("docukore_app", "docukore_migrator", "docukore_test_runner").contains(administrator))
        {
            throw new BootstrapFailure("BOOTSTRAP_AUTHORITY_INVALID");
        }
        String administratorPassword = credential(values, "DOCUKORE_BOOTSTRAP_PASSWORD");
        String version = values.getOrDefault("DOCUKORE_BOOTSTRAP_EXPECTED_SERVER_VERSION", "18");
        if (version == null || !version.matches("18(?:\\.[0-9]{1,2})?"))
        {
            throw new BootstrapFailure("BOOTSTRAP_VERSION_CONFIG_INVALID");
        }
        Map<String, String> passwords = new LinkedHashMap<>();
        if (mode != BootstrapMode.TEST)
        {
            requireEndpoint(values, "DB_URL", "docukore", endpoint);
            requireIdentity(values, "DB_USERNAME", "docukore_app");
            passwords.put("docukore_app", credential(values, "DB_PASSWORD"));
            for (String prefix : Set.of("DOCUKORE_MIGRATION_", "DOCUKORE_PROVISIONING_"))
            {
                requireEndpoint(values, prefix + "URL", "docukore", endpoint);
                requireIdentity(values, prefix + "USERNAME", "docukore_migrator");
                requireSamePassword(passwords, "docukore_migrator", credential(values, prefix + "PASSWORD"));
            }
        }
        if (mode != BootstrapMode.DEVELOPMENT)
        {
            requireEndpoint(values, "DOCUKORE_TEST_JDBC_URL", "docukore_test", endpoint);
            requireIdentity(values, "DOCUKORE_TEST_USERNAME", "docukore_test_runner");
            passwords.put("docukore_test_runner", credential(values, "DOCUKORE_TEST_PASSWORD"));
            requireIdentity(values, "DOCUKORE_TEST_RUNTIME_USERNAME", "docukore_app");
            requireSamePassword(passwords, "docukore_app", credential(values, "DOCUKORE_TEST_RUNTIME_PASSWORD"));
        }
        BootstrapConfiguration configuration = new BootstrapConfiguration(endpoint, administrator,
            administratorPassword, version, values.get("DOCUKORE_BOOTSTRAP_ENVIRONMENT"), passwords);
        configuration.validateMode(mode);
        return configuration;
    }

    /**
     * Validate explicit reset before any network access or SQL.
     *
     * @param mode selected operation
     */
    void validateMode(BootstrapMode mode)
    {
        if (mode == BootstrapMode.RESET_DEVELOPMENT && (!"development".equals(environment) || !isLoopback()))
        {
            throw new BootstrapFailure("BOOTSTRAP_RESET_TARGET_INVALID");
        }
    }

    /**
     * Build a fixed database URL preserving validated transport settings.
     *
     * @param database fixed internal database name
     * @return JDBC URL without any credentials
     */
    String url(String database)
    {
        if (!Set.of("postgres", "docukore", "docukore_test").contains(database))
        {
            throw new BootstrapFailure("BOOTSTRAP_DATABASE_INVALID");
        }
        return "jdbc:postgresql://" + endpoint.getRawAuthority() + "/" + database
            + (endpoint.getRawQuery() == null ? "" : "?" + endpoint.getRawQuery());
    }

    /**
     * Return the expected server version; major 18 is the default.
     *
     * @return version policy
     */
    String expectedVersion()
    {
        return expectedVersion;
    }

    /**
     * Access the configured existing administrator identity.
     *
     * @return bootstrap role name
     */
    String bootstrapUsername()
    {
        return bootstrapUsername;
    }

    /**
     * Access the existing bootstrap credential privately.
     *
     * @return administrator password
     */
    String bootstrapPassword()
    {
        return bootstrapPassword;
    }

    /**
     * Access a supplied credential; absent or existing roles use exactly the same supplied value.
     *
     * @param role fixed restricted role selected by the mode
     * @return supplied credential, never suitable for logging
     */
    String rolePassword(String role)
    {
        if (!passwords.containsKey(role))
        {
            throw new BootstrapFailure("BOOTSTRAP_CREDENTIAL_KEY_INVALID");
        }
        return passwords.get(role);
    }

    /**
     * Require bounded, single-line credentials without disclosing a rejected value.
     *
     * @param password supplied credential
     */
    static void validatePassword(String password)
    {
        if (password == null || password.isBlank() || password.length() > 2048
            || password.chars().anyMatch(Character::isISOControl))
        {
            throw new BootstrapFailure("BOOTSTRAP_CREDENTIAL_INVALID");
        }
    }

    /**
     * Accept literal loopback addresses only; DNS aliases cannot authorize destruction.
     *
     * @return whether the configured endpoint is explicitly loopback
     */
    private boolean isLoopback()
    {
        return Set.of("localhost", "127.0.0.1", "[::1]", "::1").contains(endpoint.getHost());
    }

    /**
     * Require a supplied target to match the maintenance server, transport and fixed database.
     *
     * @param values supplied environment
     * @param key fixed URL key
     * @param database fixed target database
     * @param endpoint validated maintenance endpoint
     */
    private static void requireEndpoint(Map<String, String> values, String key, String database, URI endpoint)
    {
        URI target = parseEndpoint(required(values, key), database);
        int targetPort = target.getPort() == -1 ? 5432 : target.getPort();
        int maintenancePort = endpoint.getPort() == -1 ? 5432 : endpoint.getPort();
        if (!endpoint.getHost().equalsIgnoreCase(target.getHost()) || targetPort != maintenancePort
            || !Objects.equals(endpoint.getRawQuery(), target.getRawQuery()))
        {
            throw new BootstrapFailure("BOOTSTRAP_ENDPOINT_MISMATCH");
        }
    }

    /**
     * Parse a single trusted server and narrowly allow transport-only JDBC settings.
     *
     * @param url credential-free JDBC URL
     * @param database fixed expected database
     * @return validated URI
     */
    private static URI parseEndpoint(String url, String database)
    {
        if (!url.startsWith("jdbc:postgresql://") || url.length() > 2048
            || url.chars().anyMatch(Character::isISOControl))
        {
            throw new BootstrapFailure("BOOTSTRAP_URL_INVALID");
        }
        try
        {
            URI uri = new URI(url.substring("jdbc:".length()));
            if (uri.getHost() == null || uri.getUserInfo() != null || !("/" + database).equals(uri.getRawPath())
                || uri.getFragment() != null || uri.getPort() == 0 || uri.getPort() > 65535
                || uri.getRawAuthority().contains(","))
            {
                throw new BootstrapFailure("BOOTSTRAP_URL_INVALID");
            }
            if (uri.getRawQuery() != null && !uri.getRawQuery().matches(
                "sslmode=(?:disable|allow|prefer|require|verify-ca|verify-full)"))
            {
                throw new BootstrapFailure("BOOTSTRAP_URL_OPTIONS_INVALID");
            }
            return uri;
        } catch (URISyntaxException failure)
        {
            throw new BootstrapFailure("BOOTSTRAP_URL_INVALID");
        }
    }

    /**
     * Require the fixed restricted login identity without disclosing configured values.
     *
     * @param values supplied environment
     * @param key fixed username key
     * @param identity fixed role
     */
    private static void requireIdentity(Map<String, String> values, String key, String identity)
    {
        if (!identity.equals(required(values, key)))
        {
            throw new BootstrapFailure("BOOTSTRAP_ROLE_INVALID");
        }
    }

    /**
     * Require one credential for every reference to the same role.
     *
     * @param passwords selected role credentials
     * @param role fixed role
     * @param password supplied credential
     */
    private static void requireSamePassword(Map<String, String> passwords, String role, String password)
    {
        String existing = passwords.putIfAbsent(role, password);
        if (existing != null && !existing.equals(password))
        {
            throw new BootstrapFailure("BOOTSTRAP_CREDENTIAL_CONFLICT");
        }
    }

    /**
     * Access and validate a supplied credential without normalization.
     *
     * @param values supplied environment
     * @param key fixed credential key
     * @return exact supplied credential
     */
    private static String credential(Map<String, String> values, String key)
    {
        String value = required(values, key);
        validatePassword(value);
        return value;
    }

    /**
     * Get a required environment value without echoing its name or value on failure.
     *
     * @param values supplied environment
     * @param key source-controlled environment key
     * @return required value
     */
    private static String required(Map<String, String> values, String key)
    {
        String value = values.get(key);
        if (value == null || value.isBlank() || value.matches("<.*>"))
        {
            throw new BootstrapFailure("BOOTSTRAP_CONFIG_INCOMPLETE");
        }
        return value;
    }
}
