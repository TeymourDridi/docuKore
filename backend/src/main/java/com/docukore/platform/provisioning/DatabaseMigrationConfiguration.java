package com.docukore.platform.provisioning;

import com.docukore.platform.DatabaseRoleVerifier;
import com.docukore.platform.PlatformProperties;
import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.boot.flyway.autoconfigure.FlywayProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Objects;

/** Explicit migration credentials are validated before Flyway can adopt runtime datasource defaults. */
@Configuration(proxyBeanMethods = false)
public class DatabaseMigrationConfiguration
{
    @Bean
    FlywayConfigurationCustomizer explicitMigrationCredentials(FlywayProperties flyway,
            DataSourceProperties runtime, CabinetProvisioningProperties provisioning)
    {
        validateConfiguration(flyway, runtime, provisioning);
        return configuration -> configuration.cleanDisabled(true);
    }

    @Bean
    FlywayMigrationStrategy verifiedPlatformMigration(FlywayProperties flyway, DataSourceProperties runtime,
            CabinetProvisioningProperties provisioning, PlatformProperties platform)
    {
        validateConfiguration(flyway, runtime, provisioning);
        return migrations ->
        {
            try (Connection migration = DriverManager.getConnection(
                    flyway.getUrl(), flyway.getUser(), flyway.getPassword());
                 Connection application = DriverManager.getConnection(
                    runtime.getUrl(), runtime.getUsername(), runtime.getPassword()))
            {
                DatabaseRoleVerifier.verifyMigration(migration);
                DatabaseRoleVerifier.verifyRuntime(application);
                migrations.migrate();
                DatabaseRoleVerifier.verifyPlatformAccess(application, platform.schema());
            } catch (SQLException | RuntimeException failure)
            {
                // Never propagate connection configuration, SQL, credentials or provider error text.
                throw new IllegalStateException("DATABASE_MIGRATION_STARTUP_FAILED");
            }
        };
    }

    /**
     * Pure validation also supports focused tests without any database connection.
     *
     * @param flyway explicit platform migration configuration
     * @param runtime application datasource configuration
     * @param provisioning dedicated tenant migration configuration
     */
    static void validateConfiguration(FlywayProperties flyway, DataSourceProperties runtime,
                                      CabinetProvisioningProperties provisioning)
    {
        if (!flyway.isEnabled() || blank(flyway.getUrl()) || blank(flyway.getUser()) || blank(flyway.getPassword())
            || blank(runtime.getUrl()) || blank(runtime.getPassword())
            || !DatabaseRoleVerifier.RUNTIME_ROLE.equals(runtime.getUsername())
            || !Objects.equals(runtime.getUrl(), flyway.getUrl())
            || !Objects.equals(flyway.getUrl(), provisioning.url())
            || !Objects.equals(flyway.getUser(), provisioning.username())
            || !Objects.equals(flyway.getPassword(), provisioning.password()))
        {
            throw new IllegalStateException("DATABASE_CREDENTIAL_CONFIGURATION_REQUIRED");
        }
        try
        {
            URI uri = URI.create(flyway.getUrl().replaceFirst("^jdbc:", ""));
            String expectedRole = "/docukore_test".equals(uri.getPath())
                ? "docukore_test_runner" : "docukore_migrator";
            if (!flyway.getUrl().startsWith("jdbc:postgresql://") || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getFragment() != null || !expectedRole.equals(flyway.getUser())
                || !("/docukore".equals(uri.getPath()) || "/docukore_test".equals(uri.getPath())))
            {
                throw new IllegalArgumentException();
            }
            provisioning.requireEnabled();
        } catch (RuntimeException failure)
        {
            throw new IllegalStateException("DATABASE_CREDENTIAL_CONFIGURATION_INVALID");
        }
    }

    private static boolean blank(String value)
    {
        return value == null || value.isBlank();
    }
}
