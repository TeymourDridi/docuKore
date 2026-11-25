package com.docukore.platform.provisioning;

import org.junit.jupiter.api.Test;
import org.springframework.boot.flyway.autoconfigure.FlywayProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Pure startup validation fails before Flyway can fall back to an application identity. */
class DatabaseMigrationConfigurationTest
{
    @Test
    void explicitlySeparatedCredentialsAreAccepted()
    {
        assertDoesNotThrow(() -> validate("docukore", "docukore_migrator", "docukore_app", "synthetic"));
        assertDoesNotThrow(() -> validate("docukore_test", "docukore_test_runner", "docukore_app", "synthetic"));
    }

    @Test
    void missingMigrationCredentialsCannotFallbackToRuntime()
    {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
            () -> validate("docukore", null, "docukore_app", "synthetic"));
        assertEquals("DATABASE_CREDENTIAL_CONFIGURATION_REQUIRED", failure.getMessage());
    }

    @Test
    void bootstrapAndRuntimeMigrationIdentitiesAreRejected()
    {
        for (String user : new String[] {"docukore", "docukore_app", "docukore_test_runner"})
        {
            assertEquals("DATABASE_CREDENTIAL_CONFIGURATION_INVALID", assertThrows(IllegalStateException.class,
                () -> validate("docukore", user, "docukore_app", "synthetic")).getMessage());
        }
        assertEquals("DATABASE_CREDENTIAL_CONFIGURATION_REQUIRED", assertThrows(IllegalStateException.class,
            () -> validate("docukore", "docukore_migrator", "docukore", "synthetic")).getMessage());
    }

    @Test
    void provisioningCannotUseDifferentCredentials()
    {
        FlywayProperties migrations = migrations("docukore", "docukore_migrator");
        DataSourceProperties runtime = runtime("docukore", "docukore_app");
        CabinetProvisioningProperties provisioning = new CabinetProvisioningProperties(
            migrations.getUrl(), migrations.getUser(), "different-synthetic-secret", null, null, null, null);
        assertEquals("DATABASE_CREDENTIAL_CONFIGURATION_REQUIRED", assertThrows(IllegalStateException.class,
            () -> DatabaseMigrationConfiguration.validateConfiguration(migrations, runtime, provisioning))
                .getMessage());
    }

    private void validate(String database, String migrationUser, String runtimeUser, String password)
    {
        FlywayProperties migrations = migrations(database, migrationUser);
        DatabaseMigrationConfiguration.validateConfiguration(migrations, runtime(database, runtimeUser),
            new CabinetProvisioningProperties(migrations.getUrl(), migrationUser, password, null, null, null, null));
    }

    private FlywayProperties migrations(String database, String username)
    {
        FlywayProperties properties = new FlywayProperties();
        properties.setUrl("jdbc:postgresql://localhost:5432/" + database);
        properties.setUser(username);
        properties.setPassword("synthetic");
        return properties;
    }

    private DataSourceProperties runtime(String database, String username)
    {
        DataSourceProperties properties = new DataSourceProperties();
        properties.setUrl("jdbc:postgresql://localhost:5432/" + database);
        properties.setUsername(username);
        properties.setPassword("synthetic-runtime");
        return properties;
    }
}
