package com.docukore.platform.migration;

import com.docukore.support.PostgresMigrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class PlatformMigrationIntegrationTest extends PostgresMigrationTest
{

    @Override
    protected boolean migratePlatformAtStart()
    {
        return false;
    }

    @BeforeEach
    void setupIsolatedPlatform()
    {
        platformSchema = newSchema("platform");
        jdbc = new JdbcTemplate(dataSource);
        transactions = new TransactionTemplate(new JdbcTransactionManager(dataSource));
        platformFlyway(platformSchema, "latest").migrate();
    }

    @AfterEach
    void cleanupPlatformSchema()
    {
        if (platformSchema != null)
        {
            // Per-test cabinet schemas tracked via OwnedTestDatabase; no global scan cleanup
            try (Connection conn = DriverManager.getConnection(SHARED_JDBC_URL, SHARED_USER, SHARED_PASSWORD))
            {
                String seqSql = "ALTER SEQUENCE IF EXISTS " + platformSchema
                        + ".cabinet_schema_number_seq RESTART WITH 0";
                jdbc.execute(seqSql);
            } catch (Exception e)
            {
                System.err.println("Warning: Failed to reset sequence for " + platformSchema + ": " + e.getMessage());
            }
        }
    }

    protected org.flywaydb.core.Flyway platformFlyway(String schema, String target)
    {
        return org.flywaydb.core.Flyway.configure().dataSource(SHARED_JDBC_URL, SHARED_USER, SHARED_PASSWORD)
            .schemas(schema)
            .defaultSchema(schema)
            .locations("classpath:db/migration/platform")
            .target(target)
            .cleanDisabled(true)
            .baselineOnMigrate(false)
            .load();
    }

    private UUID user(String username, String email)
    {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO " + platformSchema + ".users "
            + "(id, username, username_normalized, email, email_normalized, password_hash, created_at) "
            + "VALUES (?, ?, ?, ?, ?, 'fictional-hash', now())", id, username, username, email, email);
        return id;
    }

    private UUID cabinet(UUID owner)
    {
        return cabinetWithAllocation(owner);
    }

    private UUID membership(UUID cabinet, UUID owner)
    {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO " + platformSchema + ".cabinet_memberships "
            + "(id, cabinet_id, user_id, membership_role, status) VALUES (?, ?, ?, 'OWNER', 'ACTIVE')",
            id, cabinet, owner);
        return id;
    }

    @Test
    void multipleNullEmailsAreAllowed()
    {
        user("first", null);
        user("second", null);
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM " + platformSchema + ".users", Integer.class));
    }

    @Test
    void blankEmailIsRejected()
    {
        assertDatabaseError("23514", "email_not_blank", () -> user("first", ""));
    }

    @Test
    void normalizedEmailIsUnique()
    {
        user("first", "fictional@example.invalid");
        assertDatabaseError("23505", "idx_users_email_normalized_unique",
            () -> user("second", "fictional@example.invalid"));
    }

    @Test
    void normalizedUsernameIsUniqueWithoutClaimingToNormalizeIt()
    {
        user("same", null);
        assertDatabaseError("23505", "users_username_normalized_key", () -> user("same", null));
    }

    @Test
    void invitationProtectsCreatorWhoDoesNotOwnCabinet()
    {
        UUID owner = user("owner", null);
        UUID creator = user("inviter", null);
        UUID cabinet = cabinet(owner);
        UUID invitation = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO " + platformSchema + ".invitations "
                + "(id, cabinet_id, invited_email_or_identifier, token_hash, staff_type, "
                + "created_at, created_by, expires_at) "
                + "VALUES (?, ?, 'fictional@example.invalid', 'fictional-token', "
                + "'DOCTOR', now(), ?, now() + interval '1 day')",
            invitation, cabinet, creator);
        assertDatabaseError("23001", "invitations_created_by_fkey",
            () -> jdbc.update("DELETE FROM " + platformSchema + ".users WHERE id = ?", creator));
        assertEquals(1, jdbc.queryForObject(
            "SELECT count(*) FROM " + platformSchema
                + ".invitations WHERE id = ?", Integer.class, invitation));
    }

    @Test
    void revokedPermissionCanBeGrantedAgainWithoutLosingHistory()
    {
        UUID owner = user("owner", null);
        UUID member = membership(cabinet(owner), owner);
        UUID grant = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO " + platformSchema + ".membership_permissions "
                + "(id, membership_id, permission_code, granted_at) "
                + "VALUES (?, ?, 'READ_PATIENTS', now())", grant, member);
        jdbc.update(
            "UPDATE " + platformSchema + ".membership_permissions SET revoked_at = now() WHERE id = ?", grant);
        jdbc.update(
            "INSERT INTO " + platformSchema + ".membership_permissions "
                + "(id, membership_id, permission_code, granted_at) VALUES (?, ?, 'READ_PATIENTS', now())",
            UUID.randomUUID(), member);
        assertEquals(2, jdbc.queryForObject(
            "SELECT count(*) FROM " + platformSchema
                + ".membership_permissions", Integer.class));
        assertEquals(1, jdbc.queryForObject(
            "SELECT count(*) FROM " + platformSchema
                + ".membership_permissions WHERE revoked_at IS NULL", Integer.class));
    }

    @Test
    void ownerMembershipCanBeStoredExplicitly()
    {
        UUID owner = user("owner", null);
        UUID cabinet = cabinet(owner);
        membership(cabinet, owner);
        assertEquals(1, jdbc.queryForObject(
            "SELECT count(*) FROM " + platformSchema
                + ".cabinet_memberships WHERE cabinet_id = ? AND user_id = ? AND membership_role = 'OWNER'",
            Integer.class, cabinet, owner));
    }

    @Test
    void databaseCurrentlyAllowsOwnerRevocationAndApplicationMustPreventIt()
    {
        UUID owner = user("owner", null);
        UUID cabinet = cabinet(owner);
        UUID member = membership(cabinet, owner);
        jdbc.update(
            "UPDATE " + platformSchema + ".cabinet_memberships SET revoked_at = now() WHERE id = ?", member);
        assertEquals(0, jdbc.queryForObject(
            "SELECT count(*) FROM " + platformSchema
                + ".cabinet_memberships WHERE revoked_at IS NULL", Integer.class));
        assertEquals(1, jdbc.queryForObject(
            "SELECT count(*) FROM " + platformSchema + ".cabinets", Integer.class));
    }

    @Test
    void upgradingAnExistingInstallationPermanentlyClosesSetup()
    {
        String existing = newSchema("existing");
        org.flywaydb.core.Flyway.configure()
            .dataSource(dataSource.getJdbcUrl(), dataSource.getUsername(), dataSource.getPassword())
            .schemas(existing).locations("classpath:db/migration/platform").target("7").load().migrate();
        UUID id = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO " + existing + ".users (id,username,username_normalized,password_hash,created_at) "
                + "VALUES (?, 'fictional', 'fictional', 'fixture', now())", id);
        org.flywaydb.core.Flyway.configure()
            .dataSource(dataSource.getJdbcUrl(), dataSource.getUsername(), dataSource.getPassword())
            .schemas(existing).locations("classpath:db/migration/platform").load().migrate();
        assertNotNull(jdbc.queryForObject(
            "SELECT completed_at FROM " + existing
                + ".installation_setup WHERE id=1", Object.class));
        jdbc.update("DELETE FROM " + existing + ".users WHERE id=?", id);
        assertNotNull(jdbc.queryForObject(
            "SELECT completed_at FROM " + existing
                + ".installation_setup WHERE id=1", Object.class));
    }

    @Test
    void platformHasExactlyElevenSuccessfulVersionedMigrations()
    {
        assertEquals(List.of("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11"), jdbc.queryForList(
            "SELECT version FROM " + platformSchema
                + ".flyway_schema_history WHERE success AND version IS NOT NULL ORDER BY installed_rank",
            String.class));
    }
}