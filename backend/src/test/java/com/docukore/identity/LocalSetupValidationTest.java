package com.docukore.identity;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import com.docukore.support.PostgresMigrationTest;
import org.flywaydb.core.Flyway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalSetupValidationTest extends PostgresMigrationTest
{
    @Test
    void invalidSetupRollsBackAndUnicodeBcryptLimitDoesNotSilentlyTruncate()
    {
        var transactions = new TransactionTemplate(new JdbcTransactionManager(dataSource));
        String[] rejected = {"short", "é".repeat(37), "a".repeat(73)};
        for (String password : rejected)
        {
            // Use fresh schema for each attempt to test rollback behavior
            String schema = newSchema("platform");
            Flyway.configure().dataSource(dataSource.getJdbcUrl(), dataSource.getUsername(),
                    dataSource.getPassword())
                .schemas(schema)
                .defaultSchema(schema)
                .locations("classpath:db/migration/platform")
                .load()
                .migrate();

            var repository = new AccountRepository(dataSource, schema);
            var service = new LocalSetupService(
                    new DeploymentProperties(DeploymentProperties.Mode.LOCAL),
                    repository,
                    new BCryptPasswordEncoder());
            assertThrows(IllegalArgumentException.class, () ->
                transactions.executeWithoutResult(status ->
                    service.initialize(new LocalSetupService.SetupRequest(
                        "doctor", "Fictional Doctor", password))));
            assertTrue(service.available());
            assertEquals(0,
                new JdbcTemplate(dataSource)
                    .queryForObject("SELECT count(*) FROM " + schema + ".users", Integer.class));
        }
        // Test successful setup
        String schema = newSchema("platform");
        Flyway.configure().dataSource(dataSource.getJdbcUrl(), dataSource.getUsername(),
                    dataSource.getPassword())
                .schemas(schema)
                .defaultSchema(schema)
                .locations("classpath:db/migration/platform")
                .load()
                .migrate();
        var repository = new AccountRepository(dataSource, schema);
        var service = new LocalSetupService(
                    new DeploymentProperties(DeploymentProperties.Mode.LOCAL),
                    repository,
                    new BCryptPasswordEncoder());
        transactions.executeWithoutResult(status ->
                service.initialize(new LocalSetupService.SetupRequest(
                    "doctor", "Fictional Doctor", "a".repeat(72))));
        assertFalse(service.available());
    }
}
