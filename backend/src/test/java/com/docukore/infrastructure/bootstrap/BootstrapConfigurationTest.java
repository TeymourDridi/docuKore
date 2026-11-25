package com.docukore.infrastructure.bootstrap;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Supplied configuration validation without password generation, persistence or network access. */
class BootstrapConfigurationTest
{
    static Map<String, String> fixture()
    {
        Map<String, String> values = new HashMap<>();
        values.put("DOCUKORE_BOOTSTRAP_URL", "jdbc:postgresql://localhost:5432/postgres");
        values.put("DOCUKORE_BOOTSTRAP_USERNAME", "docukore");
        values.put("DOCUKORE_BOOTSTRAP_PASSWORD", "fictional-admin");
        values.put("DB_URL", "jdbc:postgresql://localhost:5432/docukore");
        values.put("DB_USERNAME", "docukore_app");
        values.put("DB_PASSWORD", "fictional-app");
        for (String prefix : Set.of("DOCUKORE_MIGRATION_", "DOCUKORE_PROVISIONING_"))
        {
            values.put(prefix + "URL", "jdbc:postgresql://localhost:5432/docukore");
            values.put(prefix + "USERNAME", "docukore_migrator");
            values.put(prefix + "PASSWORD", "fictional-migrator");
        }
        values.put("DOCUKORE_TEST_JDBC_URL", "jdbc:postgresql://localhost:5432/docukore_test");
        values.put("DOCUKORE_TEST_USERNAME", "docukore_test_runner");
        values.put("DOCUKORE_TEST_PASSWORD", "fictional-runner");
        values.put("DOCUKORE_TEST_RUNTIME_USERNAME", "docukore_app");
        values.put("DOCUKORE_TEST_RUNTIME_PASSWORD", "fictional-app");
        return values;
    }

    @Test
    void usesExactSuppliedPasswordsAndIgnoresTestConfigurationDuringNormalStartup()
    {
        Map<String, String> values = fixture();
        values.put("DB_PASSWORD", "fictional'\\${user.home}#=");
        values.put("DOCUKORE_TEST_PASSWORD", "");
        values.put("DOCUKORE_TEST_JDBC_URL", "not-a-url");
        BootstrapConfiguration configuration = BootstrapConfiguration.fromEnvironment(values,
            BootstrapMode.DEVELOPMENT);
        assertEquals("fictional'\\${user.home}#=", configuration.rolePassword("docukore_app"));
        assertEquals("jdbc:postgresql://localhost:5432/docukore", configuration.url("docukore"));
        assertThrows(BootstrapFailure.class, () -> configuration.rolePassword("docukore_test_runner"));
    }

    @Test
    void testInitializationRequiresNoApplicationMigrationConfiguration()
    {
        Map<String, String> values = fixture();
        values.remove("DB_PASSWORD");
        values.remove("DOCUKORE_MIGRATION_PASSWORD");
        BootstrapConfiguration configuration = BootstrapConfiguration.fromEnvironment(values, BootstrapMode.TEST);
        assertEquals("fictional-runner", configuration.rolePassword("docukore_test_runner"));
        assertThrows(BootstrapFailure.class, () -> configuration.rolePassword("docukore_migrator"));
    }

    @Test
    void requiresEverySelectedSettingAndNeverGeneratesPasswords()
    {
        for (String key : fixture().keySet())
        {
            if (!key.startsWith("DOCUKORE_TEST_"))
            {
                Map<String, String> values = fixture();
                values.remove(key);
                assertThrows(BootstrapFailure.class,
                    () -> BootstrapConfiguration.fromEnvironment(values, BootstrapMode.DEVELOPMENT));
                values.put(key, "<placeholder>");
                assertThrows(BootstrapFailure.class,
                    () -> BootstrapConfiguration.fromEnvironment(values, BootstrapMode.DEVELOPMENT));
            }
        }
    }

    @Test
    void rejectsWrongRolesTargetsAndInconsistentMigratorCredentials()
    {
        for (Map.Entry<String, String> replacement : Map.of("DB_USERNAME", "docukore",
            "DOCUKORE_PROVISIONING_PASSWORD", "different", "DB_URL", "jdbc:postgresql://remote:5432/docukore",
            "DOCUKORE_BOOTSTRAP_URL", "jdbc:postgresql://localhost:5432/docukore").entrySet())
        {
            Map<String, String> values = fixture();
            values.put(replacement.getKey(), replacement.getValue());
            assertThrows(BootstrapFailure.class,
                () -> BootstrapConfiguration.fromEnvironment(values, BootstrapMode.DEVELOPMENT));
        }
    }

    @Test
    void rejectsUrlCredentialsDriverOptionsAndMalformedEndpoints()
    {
        for (String url : Set.of("jdbc:postgresql://admin:fictional@localhost/postgres",
            "jdbc:postgresql://localhost/postgres?password=fictional",
            "jdbc:postgresql://localhost/postgres?options=fictional", "jdbc:postgresql://localhost,remote/postgres",
            "jdbc:postgresql://localhost/postgres#fragment", "jdbc:postgresql://localhost:0/postgres"))
        {
            Map<String, String> values = fixture();
            values.put("DOCUKORE_BOOTSTRAP_URL", url);
            assertThrows(BootstrapFailure.class,
                () -> BootstrapConfiguration.fromEnvironment(values, BootstrapMode.DEVELOPMENT));
        }
    }

    @Test
    void acceptsConfiguredExistingInstallerAdministrator()
    {
        Map<String, String> values = fixture();
        values.put("DOCUKORE_BOOTSTRAP_USERNAME", "installer_admin");
        assertEquals("installer_admin", BootstrapConfiguration.fromEnvironment(values, BootstrapMode.DEVELOPMENT)
            .bootstrapUsername());
    }

    @Test
    void resetRequiresExplicitDevelopmentAndLiteralLoopback()
    {
        Map<String, String> values = fixture();
        assertThrows(BootstrapFailure.class,
            () -> BootstrapConfiguration.fromEnvironment(values, BootstrapMode.RESET_DEVELOPMENT));
        values.put("DOCUKORE_BOOTSTRAP_ENVIRONMENT", "development");
        BootstrapConfiguration.fromEnvironment(values, BootstrapMode.RESET_DEVELOPMENT);
        values.replaceAll((key, value) -> key.endsWith("URL") ? value.replace("localhost", "remote") : value);
        assertThrows(BootstrapFailure.class,
            () -> BootstrapConfiguration.fromEnvironment(values, BootstrapMode.RESET_DEVELOPMENT));
    }
}
