package com.docukore.platform.provisioning;

import com.docukore.platform.PlatformProperties;
import com.docukore.platform.cabinet.CabinetRegistrationRepository;
import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Cabinet provisioning service implementing ADR-0005 approved sequence.
 * Uses dedicated privileged connection with advisory locks for recoverable serialized provisioning.
 */
@Service
public class CabinetProvisioningService
{
    /** Class-local diagnostics never include provisioning identities or exception details. */
    private static final Logger LOG = LoggerFactory.getLogger(CabinetProvisioningService.class);

    /** Exact finite diagnostic vocabulary; public and persisted error sanitization remain independent. */
    private static final Set<String> SAFE_DIAGNOSTIC_CODES = Set.of(
        "PROVISIONING_DISABLED", "PROVISIONING_CONFIGURATION", "REGISTRATION_NOT_FOUND",
        "ACCOUNT_DOES_NOT_OWN_REGISTRATION", "CABINET_NOT_FOUND", "ACCOUNT_NOT_ACTIVE",
        "ORIGINAL_REGISTRATION_NOT_FOUND", "REGISTRATION_CABINET_MISMATCH", "CABINET_OWNER_MISMATCH",
        "CABINET_SCHEMA_MISMATCH", "CABINET_NOT_ACTIVE", "MEMBERSHIP_MISSING", "TRIAL_SUBSCRIPTION_MISSING",
        "ACCOUNT_NOT_ELIGIBLE", "EMAIL_VERIFICATION_REQUIRED", "CABINET_NOT_ELIGIBLE",
        "TENANT_MIGRATIONS_INCOMPLETE", "PROVISIONING_LOCK_LOST", "PROVISIONING_ATTEMPT_MISMATCH",
        "PROVISIONING_CONNECTION_FAILED", "PROVISIONING_TIMEOUT", "PROVISIONING_DATABASE_ERROR", "PROVISIONING_FAILED");

    /** Repository for cabinet registration allocation operations. */
    private final CabinetRegistrationRepository registrationRepository;

    /** Provisioning configuration properties. */
    private final CabinetProvisioningProperties properties;

    /** JDBC template for platform schema operations. */
    private final JdbcTemplate platformJdbc;

    /** Platform schema name for qualified table references. */
    private final String platformSchema;

    public CabinetProvisioningService(
        CabinetRegistrationRepository registrationRepository,
        CabinetProvisioningProperties properties,
        JdbcTemplate platformJdbc,
        PlatformProperties platform)
    {
        this.registrationRepository = registrationRepository;
        this.properties = properties;
        this.platformJdbc = platformJdbc;
        this.platformSchema = platform.schema();
    }

    /**
     * Provision a cabinet for the given registration.
     * Uses advisory locks to ensure serialized recoverable provisioning.
     * Safe for retry on failure or IN_PROGRESS status.
     *
     * @param registrationId the original registration UUID
     * @param accountId the account UUID (verified against registration)
     * @return provisioning result with cabinet UUID and status
     */
    public ProvisioningResult provision(UUID registrationId, UUID accountId)
    {
        String phase = "CONFIGURATION";
        LOG.debug("event=CABINET_PROVISIONING_BEGIN phase=CONFIGURATION");
        CabinetRegistrationRepository.Allocation allocation;
        try
        {
            // Step 1: Verify provisioning capability is enabled
            properties.requireEnabled();

            // Step 2: Validate account ownership against original registration before any allocation
            phase = "OWNERSHIP";
            validateAccountOwnsRegistration(registrationId, accountId);

            // Step 3: Call idempotent allocation boundary (REQUIRES_NEW transaction)
            phase = "ALLOCATION";
            allocation = registrationRepository.allocate(registrationId);
        } catch (RuntimeException failure)
        {
            LOG.warn("event=CABINET_PROVISIONING_FAILED phase={} code={}", phase, safeDiagnosticCode(failure));
            throw failure;
        }
        LOG.debug("event=CABINET_PROVISIONING_PHASE phase=ALLOCATION_RETURNED");
        UUID cabinetId = allocation.cabinetId();
        String schemaName = allocation.schemaName();
        UUID reservationId = allocation.reservationId();

        // One physical session owns the stable cabinet lock through validation or recovery.
        UUID attemptId = null;
        phase = "CONNECTION";
        try (Connection provisioningConn = openProvisioningConnection())
        {
            provisioningConn.setAutoCommit(false);

            // Acquire per-cabinet advisory lock
            phase = "LOCK";
            if (!acquireAdvisoryLock(provisioningConn, cabinetId))
            {
                LOG.debug("event=CABINET_PROVISIONING_LOCK_CONTENDED outcome=IN_PROGRESS");
                return new ProvisioningResult(cabinetId, ProvisioningStatus.IN_PROGRESS);
            }

            try
            {
                phase = "STATUS_CHECK";
                // A predecessor may have completed before this caller acquired L.
                try (PreparedStatement statusStmt = provisioningConn.prepareStatement(
                    "SELECT provisioning_status FROM \"" + platformSchema + "\".cabinets WHERE id = ?"))
                {
                    statusStmt.setObject(1, cabinetId);
                    try (ResultSet rs = statusStmt.executeQuery())
                    {
                        if (!rs.next())
                        {
                            throw new CabinetProvisioningException("CABINET_NOT_FOUND");
                        }
                        if ("ACTIVE".equals(rs.getString(1)))
                        {
                            phase = "COMPLETED_VALIDATION";
                            ProvisioningResult completed = validateCompletedProvisioning(
                                provisioningConn, cabinetId, accountId, schemaName, reservationId);
                            LOG.debug("event=CABINET_PROVISIONING_COMPLETED_REUSE outcome=ACTIVE");
                            return completed;
                        }
                    }
                }

                phase = "CLAIM";
                // C is unpooled and independent of both L and any ambient Spring transaction.
                try (Connection claimConn = openProvisioningConnection())
                {
                    claimConn.setAutoCommit(false);
                    attemptId = claimProvisioningAttempt(
                        claimConn, provisioningConn, registrationId, cabinetId, accountId, schemaName);
                    // Keep the token even if commit acknowledgement or C's close fails.
                    claimConn.commit();
                    LOG.debug("event=CABINET_PROVISIONING_PHASE phase=CLAIM_COMMITTED");
                }

                phase = "SCHEMA_RECEIPT";
                requireAttemptOwnership(provisioningConn, cabinetId, attemptId);
                // Create or validate the immutable schema/receipt pair in the same transaction.
                createSchemaWithReceipt(provisioningConn, cabinetId, schemaName, reservationId);
                requireAttemptOwnership(provisioningConn, cabinetId, attemptId);
                provisioningConn.commit();
                LOG.debug("event=CABINET_PROVISIONING_PHASE phase=SCHEMA_RECEIPT_COMMITTED");

                phase = "MIGRATIONS";
                // Do not retain row locks across independently connected Flyway execution.
                requireAttemptOwnership(provisioningConn, cabinetId, attemptId);
                provisioningConn.commit();
                applyTenantMigrations(schemaName);
                LOG.debug("event=CABINET_PROVISIONING_PHASE phase=MIGRATIONS_RETURNED");
                phase = "ACTIVATION";
                requireAttemptOwnership(provisioningConn, cabinetId, attemptId);

                // Finalize cabinet while holding the original advisory-lock session.
                finalizeProvisioning(provisioningConn, cabinetId, accountId, schemaName, reservationId, attemptId);
                provisioningConn.commit();
                LOG.info("event=CABINET_PROVISIONING_ACTIVATED phase=ACTIVATION_COMMITTED outcome=ACTIVE");

                return new ProvisioningResult(cabinetId, ProvisioningStatus.ACTIVE);
            } catch (Exception e)
            {
                LOG.warn("event=CABINET_PROVISIONING_FAILED phase={} code={}", phase, safeDiagnosticCode(e));
                try
                {
                    provisioningConn.rollback();
                } catch (SQLException rollbackFailure)
                {
                    e.addSuppressed(rollbackFailure);
                    // An unusable L must release row locks before the independent failure transaction.
                    try
                    {
                        provisioningConn.close();
                    } catch (SQLException closeFailure)
                    {
                        e.addSuppressed(closeFailure);
                    }
                }
                if (attemptId != null)
                {
                    recordProvisioningFailure(cabinetId, attemptId, e);
                }
                throw new CabinetProvisioningException(sanitizeErrorCode(e));
            }
        } catch (SQLException connectionFailure)
        {
            LOG.warn("event=CABINET_PROVISIONING_FAILED phase={} code=PROVISIONING_CONNECTION_FAILED", phase);
            throw new CabinetProvisioningException("PROVISIONING_CONNECTION_FAILED");
        }
    }

    private void validateAccountOwnsRegistration(UUID registrationId, UUID accountId)
    {
        UUID regAccountId = platformJdbc.queryForObject(
            "SELECT account_id FROM \"" + platformSchema + "\".initial_cabinet_registrations WHERE id = ?",
            UUID.class, registrationId);
        if (regAccountId == null)
        {
            throw new CabinetProvisioningException("REGISTRATION_NOT_FOUND");
        }
        if (!accountId.equals(regAccountId))
        {
            throw new CabinetProvisioningException("ACCOUNT_DOES_NOT_OWN_REGISTRATION");
        }
    }

    private ProvisioningResult validateCompletedProvisioning(Connection conn, UUID cabinetId, UUID accountId,
                                                             String schemaName, UUID reservationId)
        throws SQLException
    {
        // Lock account, registration, cabinet in order
        try (PreparedStatement accountLock = conn.prepareStatement(
            "SELECT id, account_status FROM \"" + platformSchema + "\".users WHERE id = ? FOR UPDATE"))
        {
            accountLock.setObject(1, accountId);
            try (ResultSet rs = accountLock.executeQuery())
            {
                if (!rs.next() || !"ACTIVE".equals(rs.getString("account_status")))
                {
                    throw new CabinetProvisioningException("ACCOUNT_NOT_ACTIVE");
                }
            }
        }

        try (PreparedStatement registrationLock = conn.prepareStatement(
            "SELECT r.id, r.account_id, r.cabinet_id "
                + "FROM \"" + platformSchema + "\".initial_cabinet_registrations r "
                + "WHERE r.cabinet_id = ? "
                + "FOR UPDATE"))
        {
            registrationLock.setObject(1, cabinetId);
            try (ResultSet rs = registrationLock.executeQuery())
            {
                if (!rs.next())
                {
                    throw new CabinetProvisioningException("ORIGINAL_REGISTRATION_NOT_FOUND");
                }
                UUID regAccountId = (UUID) rs.getObject("account_id");
                if (!accountId.equals(regAccountId))
                {
                    throw new CabinetProvisioningException("ACCOUNT_DOES_NOT_OWN_REGISTRATION");
                }
                UUID regCabinetId = (UUID) rs.getObject("cabinet_id");
                if (!cabinetId.equals(regCabinetId) || rs.next())
                {
                    throw new CabinetProvisioningException("REGISTRATION_CABINET_MISMATCH");
                }
            }
        }

        try (PreparedStatement cabinetLock = conn.prepareStatement(
            "SELECT id, owner_id, provisioning_status, schema_name, administrative_status "
                + "FROM \"" + platformSchema + "\".cabinets WHERE id = ? FOR UPDATE"))
        {
            cabinetLock.setObject(1, cabinetId);
            try (ResultSet rs = cabinetLock.executeQuery())
            {
                if (!rs.next())
                {
                    throw new CabinetProvisioningException("CABINET_NOT_FOUND");
                }
                UUID ownerId = (UUID) rs.getObject("owner_id");
                if (!accountId.equals(ownerId))
                {
                    throw new CabinetProvisioningException("CABINET_OWNER_MISMATCH");
                }
                String provisioningStatus = rs.getString("provisioning_status");
                String cabinetSchema = rs.getString("schema_name");
                String administrativeStatus = rs.getString("administrative_status");

                if (!schemaName.equals(cabinetSchema))
                {
                    throw new CabinetProvisioningException("CABINET_SCHEMA_MISMATCH");
                }
                if (!"ACTIVE".equals(provisioningStatus))
                {
                    throw new CabinetProvisioningException("CABINET_NOT_ACTIVE");
                }
                // Administrative status may be SUSPENDED - do not change it
            }
        }

        // All completed evidence is read on the same lock-owning session.
        verifyExistingSchemaReceipt(conn, cabinetId, schemaName, reservationId);
        validateTenantMigrations(conn, schemaName);
        try (PreparedStatement membership = conn.prepareStatement(
            "SELECT COUNT(*) FROM \"" + platformSchema
                + "\".cabinet_memberships WHERE cabinet_id = ? AND user_id = ? AND status = 'ACTIVE'"))
        {
            membership.setObject(1, cabinetId);
            membership.setObject(2, accountId);
            try (ResultSet rs = membership.executeQuery())
            {
                if (!rs.next() || rs.getInt(1) != 1)
                {
                    throw new CabinetProvisioningException("MEMBERSHIP_MISSING");
                }
            }
        }
        try (PreparedStatement subscription = conn.prepareStatement(
            "SELECT COUNT(*) FROM \"" + platformSchema
                + "\".cabinet_subscriptions WHERE cabinet_id = ? AND subscription_status = 'TRIAL'"))
        {
            subscription.setObject(1, cabinetId);
            try (ResultSet rs = subscription.executeQuery())
            {
                if (!rs.next() || rs.getInt(1) != 1)
                {
                    throw new CabinetProvisioningException("TRIAL_SUBSCRIPTION_MISSING");
                }
            }
        }
        requireAdvisoryLock(conn, cabinetId);
        conn.commit();
        return new ProvisioningResult(cabinetId, ProvisioningStatus.ACTIVE);
    }

    private UUID claimProvisioningAttempt(Connection conn, Connection lockConn, UUID registrationId, UUID cabinetId,
                                          UUID accountId, String schemaName) throws SQLException
    {
        // Recheck eligibility under account -> original registration -> cabinet row locks on C.
        try (PreparedStatement accountLock = conn.prepareStatement(
            "SELECT account_status, deployment_mode, email_verified_at FROM \"" + platformSchema
                + "\".users WHERE id = ? FOR UPDATE"))
        {
            accountLock.setObject(1, accountId);
            try (ResultSet rs = accountLock.executeQuery())
            {
                if (!rs.next() || !"ACTIVE".equals(rs.getString("account_status")))
                {
                    throw new CabinetProvisioningException("ACCOUNT_NOT_ACTIVE");
                }
                String deploymentMode = rs.getString("deployment_mode");
                if (!"local".equals(deploymentMode) && !"hosted".equals(deploymentMode))
                {
                    throw new CabinetProvisioningException("ACCOUNT_NOT_ELIGIBLE");
                }
                if ("hosted".equals(deploymentMode) && rs.getObject("email_verified_at") == null)
                {
                    throw new CabinetProvisioningException("EMAIL_VERIFICATION_REQUIRED");
                }
            }
        }
        try (PreparedStatement registrationLock = conn.prepareStatement(
            "SELECT account_id, cabinet_id FROM \"" + platformSchema
                + "\".initial_cabinet_registrations WHERE id = ? FOR UPDATE"))
        {
            registrationLock.setObject(1, registrationId);
            try (ResultSet rs = registrationLock.executeQuery())
            {
                if (!rs.next())
                {
                    throw new CabinetProvisioningException("ORIGINAL_REGISTRATION_NOT_FOUND");
                }
                if (!accountId.equals(rs.getObject("account_id")))
                {
                    throw new CabinetProvisioningException("ACCOUNT_DOES_NOT_OWN_REGISTRATION");
                }
                if (!cabinetId.equals(rs.getObject("cabinet_id")) || rs.next())
                {
                    throw new CabinetProvisioningException("REGISTRATION_CABINET_MISMATCH");
                }
            }
        }
        try (PreparedStatement cabinetLock = conn.prepareStatement(
            "SELECT owner_id, schema_name, provisioning_status FROM \"" + platformSchema
                + "\".cabinets WHERE id = ? FOR UPDATE"))
        {
            cabinetLock.setObject(1, cabinetId);
            try (ResultSet rs = cabinetLock.executeQuery())
            {
                if (!rs.next())
                {
                    throw new CabinetProvisioningException("CABINET_NOT_FOUND");
                }
                if (!accountId.equals(rs.getObject("owner_id")))
                {
                    throw new CabinetProvisioningException("CABINET_OWNER_MISMATCH");
                }
                if (!schemaName.equals(rs.getString("schema_name")))
                {
                    throw new CabinetProvisioningException("CABINET_SCHEMA_MISMATCH");
                }
                String status = rs.getString("provisioning_status");
                if (!"PENDING".equals(status) && !"PROVISIONING_FAILED".equals(status)
                    && !"PROVISIONING".equals(status))
                {
                    throw new CabinetProvisioningException("CABINET_NOT_ELIGIBLE");
                }
            }
        }
        // Check L while C holds all claim rows: a successor cannot claim until C finishes.
        // Acquiring the stable lock, not elapsed time, proves an abandoned attempt can be replaced.
        requireAdvisoryLock(lockConn, cabinetId);
        UUID attemptId = UUID.randomUUID();
        String sql = "UPDATE \"" + platformSchema + "\".cabinets "
                + "SET provisioning_status = 'PROVISIONING', "
                + "provisioning_attempt_id = ?, last_provisioning_error = NULL "
                + "WHERE id = ? "
                + "AND provisioning_status IN ('PENDING', 'PROVISIONING_FAILED', 'PROVISIONING')";
        try (PreparedStatement stmt = conn.prepareStatement(sql))
        {
            stmt.setObject(1, attemptId);
            stmt.setObject(2, cabinetId);
            int updated = stmt.executeUpdate();
            if (updated != 1)
            {
                throw new SQLException("Cabinet not in eligible state for provisioning attempt");
            }
        }
        return attemptId;
    }

    private void validateTenantMigrations(Connection conn, String schemaName) throws SQLException
    {
        try (PreparedStatement history = conn.prepareStatement(
            "SELECT COUNT(*) FROM \"" + schemaName
                + "\".flyway_schema_history_tenant WHERE success = true"))
        {
            try (ResultSet rs = history.executeQuery())
            {
                if (!rs.next() || rs.getInt(1) == 0)
                {
                    throw new CabinetProvisioningException("TENANT_MIGRATIONS_INCOMPLETE");
                }
            }
        }
    }

    private Connection openProvisioningConnection() throws SQLException
    {
        Connection conn = DriverManager.getConnection(
            properties.url(),
            properties.username(),
            properties.password());

        // Set connection timeouts via properties (driver-level)
        int networkTimeoutMs = (int) properties.networkTimeout().toMillis();
        int statementTimeoutMs = (int) properties.statementTimeout().toMillis();
        int lockTimeoutMs = (int) properties.lockTimeout().toMillis();

        conn.setNetworkTimeout(null, networkTimeoutMs);

        // Set statement_timeout and lock_timeout via pg_catalog.set_config for parameterized execution
        try (PreparedStatement stmt = conn.prepareStatement(
            "SELECT pg_catalog.set_config('statement_timeout', ?, false)"))
        {
            stmt.setString(1, String.valueOf(statementTimeoutMs));
            stmt.execute();
        }
        try (PreparedStatement stmt = conn.prepareStatement(
            "SELECT pg_catalog.set_config('lock_timeout', ?, false)"))
        {
            stmt.setString(1, String.valueOf(lockTimeoutMs));
            stmt.execute();
        }

        return conn;
    }

    private boolean acquireAdvisoryLock(Connection conn, UUID cabinetId) throws SQLException
    {
        // Use cabinet UUID's most significant bits for lock key
        long lockKey = cabinetId.getMostSignificantBits();

        try (PreparedStatement stmt = conn.prepareStatement(
            "SELECT pg_try_advisory_lock(?)"))
        {
            stmt.setLong(1, lockKey);
            try (ResultSet rs = stmt.executeQuery())
            {
                if (rs.next())
                {
                    return rs.getBoolean(1);
                }
                throw new SQLException("Advisory lock query returned no result");
            }
        }
    }

    private void requireAdvisoryLock(Connection conn, UUID cabinetId) throws SQLException
    {
        long key = cabinetId.getMostSignificantBits();
        // Observe the lock without reentrant acquisition; commits/rollbacks leave session locks held.
        try (PreparedStatement stmt = conn.prepareStatement(
            "SELECT EXISTS (SELECT 1 FROM pg_catalog.pg_locks "
                + "WHERE locktype = 'advisory' AND granted AND mode = 'ExclusiveLock' "
                + "AND pid = pg_catalog.pg_backend_pid() "
                + "AND database = (SELECT oid FROM pg_catalog.pg_database WHERE datname = current_database()) "
                + "AND classid::bigint = ? AND objid::bigint = ? AND objsubid = 1)"))
        {
            stmt.setLong(1, key >>> 32);
            stmt.setLong(2, key & 0xffff_ffffL);
            try (ResultSet rs = stmt.executeQuery())
            {
                if (!rs.next() || !rs.getBoolean(1))
                {
                    throw new CabinetProvisioningException("PROVISIONING_LOCK_LOST");
                }
            }
        }
    }

    private void requireAttemptOwnership(Connection conn, UUID cabinetId, UUID attemptId) throws SQLException
    {
        requireAdvisoryLock(conn, cabinetId);
        // A plain read avoids holding cabinet row locks across separate Flyway connections.
        try (PreparedStatement stmt = conn.prepareStatement(
            "SELECT provisioning_status, provisioning_attempt_id FROM \"" + platformSchema
                + "\".cabinets WHERE id = ?"))
        {
            stmt.setObject(1, cabinetId);
            try (ResultSet rs = stmt.executeQuery())
            {
                if (!rs.next() || !"PROVISIONING".equals(rs.getString("provisioning_status"))
                    || !attemptId.equals(rs.getObject("provisioning_attempt_id")))
                {
                    throw new CabinetProvisioningException("PROVISIONING_ATTEMPT_MISMATCH");
                }
            }
        }
    }

    private void createSchemaWithReceipt(Connection conn, UUID cabinetId, String schemaName,
                                         UUID reservationId) throws SQLException
    {
        // Validate schema name format
        if (!schemaName.matches("^cabinet\\d{5,}$"))
        {
            throw new IllegalArgumentException("Invalid cabinet schema name format");
        }

        // A durable receipt forbids recreation even when its namespace is missing or replaced.
        try (PreparedStatement receiptCheck = conn.prepareStatement(
            "SELECT EXISTS (SELECT 1 FROM \"" + platformSchema
                + "\".cabinet_schema_creation_receipts WHERE cabinet_id = ? OR schema_name = ? OR reservation_id = ?)"))
        {
            receiptCheck.setObject(1, cabinetId);
            receiptCheck.setString(2, schemaName);
            receiptCheck.setObject(3, reservationId);
            try (ResultSet rs = receiptCheck.executeQuery())
            {
                if (!rs.next())
                {
                    throw new SQLException("Schema receipt check returned no result");
                }
                if (rs.getBoolean(1))
                {
                    verifyExistingSchemaReceipt(conn, cabinetId, schemaName, reservationId);
                    return;
                }
            }
        }

        // Existing unreceipted namespaces are never adopted.
        try (PreparedStatement checkStmt = conn.prepareStatement(
            "SELECT EXISTS(SELECT 1 FROM pg_catalog.pg_namespace WHERE nspname = ?)"))
        {
            checkStmt.setString(1, schemaName);
            try (ResultSet rs = checkStmt.executeQuery())
            {
                if (rs.next() && rs.getBoolean(1))
                {
                    // Schema exists - verify it has proper receipt
                    verifyExistingSchemaReceipt(conn, cabinetId, schemaName, reservationId);
                    return;
                }
            }
        }

        // Create schema
        try (PreparedStatement createStmt = conn.prepareStatement("CREATE SCHEMA " + schemaName))
        {
            createStmt.execute();
        }

        // Capture namespace OID and owner OID
        long namespaceOid;
        long ownerOid;
        try (PreparedStatement oidStmt = conn.prepareStatement(
            "SELECT oid::bigint, nspowner::bigint FROM pg_catalog.pg_namespace WHERE nspname = ?"))
        {
            oidStmt.setString(1, schemaName);
            try (ResultSet rs = oidStmt.executeQuery())
            {
                if (!rs.next())
                {
                    throw new SQLException("Created schema not found in catalog");
                }
                namespaceOid = rs.getLong(1);
                ownerOid = rs.getLong(2);
            }
        }

        // Insert immutable creation receipt with reservation_id
        try (PreparedStatement receiptStmt = conn.prepareStatement(
            "INSERT INTO \"" + platformSchema + "\".cabinet_schema_creation_receipts "
                + "(reservation_id, cabinet_id, schema_name, namespace_oid, owner_oid, created_at) "
                + "VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP)"))
        {
            receiptStmt.setObject(1, reservationId);
            receiptStmt.setObject(2, cabinetId);
            receiptStmt.setString(3, schemaName);
            receiptStmt.setLong(4, namespaceOid);
            receiptStmt.setLong(5, ownerOid);
            receiptStmt.executeUpdate();
        }
    }

    private void verifyExistingSchemaReceipt(Connection conn, UUID cabinetId, String schemaName,
                                             UUID reservationId) throws SQLException
    {
        try (PreparedStatement stmt = conn.prepareStatement(
            "SELECT reservation_id, namespace_oid, owner_oid FROM \"" + platformSchema
                + "\".cabinet_schema_creation_receipts WHERE cabinet_id = ? AND schema_name = ?"))
        {
            stmt.setObject(1, cabinetId);
            stmt.setString(2, schemaName);
            try (ResultSet rs = stmt.executeQuery())
            {
                if (!rs.next())
                {
                    throw new SQLException("Existing schema has no creation receipt");
                }
                UUID receiptedReservationId = (UUID) rs.getObject("reservation_id");
                if (!reservationId.equals(receiptedReservationId))
                {
                    throw new SQLException("Schema receipt reservation does not match allocation");
                }
                long receiptedNamespaceOid = rs.getLong("namespace_oid");
                long receiptedOwnerOid = rs.getLong("owner_oid");

                // Verify current catalog matches receipt
                try (PreparedStatement catalogStmt = conn.prepareStatement(
                    "SELECT oid::bigint, nspowner::bigint FROM pg_catalog.pg_namespace WHERE nspname = ?"))
                {
                    catalogStmt.setString(1, schemaName);
                    try (ResultSet catalogRs = catalogStmt.executeQuery())
                    {
                        if (!catalogRs.next())
                        {
                            throw new SQLException("Receipted schema missing from catalog");
                        }
                        long currentNamespaceOid = catalogRs.getLong(1);
                        long currentOwnerOid = catalogRs.getLong(2);

                        if (receiptedNamespaceOid != currentNamespaceOid
                            || receiptedOwnerOid != currentOwnerOid)
                        {
                            throw new SQLException(
                                "Schema catalog identity does not match creation receipt");
                        }
                    }
                }
            }
        }
    }

    private void applyTenantMigrations(String schemaName)
    {
        Flyway flyway = Flyway.configure()
            .dataSource(properties.url(), properties.username(), properties.password())
            .schemas(schemaName)
            .defaultSchema(schemaName)
            .locations("classpath:db/migration/tenant")
            .table("flyway_schema_history_tenant")
            .createSchemas(false)
            .baselineOnMigrate(false)
            .validateOnMigrate(true)
            .cleanDisabled(true)
            .load();

        flyway.migrate();
    }

    private void finalizeProvisioning(Connection conn, UUID cabinetId, UUID accountId,
                                      String schemaName, UUID reservationId, UUID attemptId)
        throws SQLException
    {
        // Lock account, registration, and cabinet rows in consistent order: account -> registration -> cabinet
        try (PreparedStatement accountLock = conn.prepareStatement(
            "SELECT id, account_status, email_verified_at, deployment_mode "
                + "FROM \"" + platformSchema + "\".users WHERE id = ? FOR UPDATE"))
        {
            accountLock.setObject(1, accountId);
            try (ResultSet rs = accountLock.executeQuery())
            {
                if (!rs.next())
                {
                    throw new SQLException("Account not found");
                }
                String accountStatus = rs.getString("account_status");
                if (!"ACTIVE".equals(accountStatus))
                {
                    throw new SQLException("Account is not active");
                }
            }
        }

        try (PreparedStatement registrationLock = conn.prepareStatement(
            "SELECT r.id, r.account_id, r.cabinet_id, r.cabinet_name "
                + "FROM \"" + platformSchema + "\".initial_cabinet_registrations r "
                + "WHERE r.cabinet_id = ? "
                + "FOR UPDATE"))
        {
            registrationLock.setObject(1, cabinetId);
            try (ResultSet rs = registrationLock.executeQuery())
            {
                if (!rs.next())
                {
                    throw new SQLException("Original registration not found");
                }
                UUID regAccountId = (UUID) rs.getObject("account_id");
                if (!accountId.equals(regAccountId))
                {
                    throw new SQLException("Account does not own registration");
                }
                UUID regCabinetId = (UUID) rs.getObject("cabinet_id");
                if (!cabinetId.equals(regCabinetId) || rs.next())
                {
                    throw new SQLException("Registration cabinet mismatch");
                }
            }
        }

        try (PreparedStatement cabinetLock = conn.prepareStatement(
            "SELECT id, owner_id, provisioning_status, schema_name, administrative_status, provisioning_attempt_id "
                + "FROM \"" + platformSchema + "\".cabinets WHERE id = ? FOR UPDATE"))
        {
            cabinetLock.setObject(1, cabinetId);
            try (ResultSet rs = cabinetLock.executeQuery())
            {
                if (!rs.next())
                {
                    throw new SQLException("Cabinet not found");
                }
                UUID ownerId = (UUID) rs.getObject("owner_id");
                if (!accountId.equals(ownerId))
                {
                    throw new SQLException("Cabinet owner does not match account");
                }
                String provisioningStatus = rs.getString("provisioning_status");
                String cabinetSchema = rs.getString("schema_name");
                String administrativeStatus = rs.getString("administrative_status");

                if (!schemaName.equals(cabinetSchema))
                {
                    throw new SQLException("Cabinet schema name mismatch");
                }
                if (!"PROVISIONING".equals(provisioningStatus))
                {
                    throw new SQLException("Cabinet not in PROVISIONING state");
                }
                if (!attemptId.equals(rs.getObject("provisioning_attempt_id")))
                {
                    throw new CabinetProvisioningException("PROVISIONING_ATTEMPT_MISMATCH");
                }
            }
        }

        requireAdvisoryLock(conn, cabinetId);
        // Validate tenant migrations completed successfully on the lock-owning session.
        validateTenantMigrations(conn, schemaName);

        // Verify creation receipt matches allocation and catalog identity
        verifyExistingSchemaReceipt(conn, cabinetId, schemaName, reservationId);

        requireAttemptOwnership(conn, cabinetId, attemptId);
        // Create OWNER/DOCTOR membership
        UUID membershipId = UUID.randomUUID();
        try (PreparedStatement membershipStmt = conn.prepareStatement(
            "INSERT INTO \"" + platformSchema
                + "\".cabinet_memberships (id, cabinet_id, user_id, membership_role, staff_type, status) "
                + "VALUES (?, ?, ?, 'OWNER', 'DOCTOR', 'ACTIVE')"))
        {
            membershipStmt.setObject(1, membershipId);
            membershipStmt.setObject(2, cabinetId);
            membershipStmt.setObject(3, accountId);
            membershipStmt.executeUpdate();
        }

        requireAttemptOwnership(conn, cabinetId, attemptId);
        // Create initial 720-hour trial subscription
        UUID subscriptionId = UUID.randomUUID();
        Instant now = Instant.now();
        Instant trialEnd = now.plusSeconds(720 * 3600);
        try (PreparedStatement subscriptionStmt = conn.prepareStatement(
            "INSERT INTO \"" + platformSchema
                + "\".cabinet_subscriptions (id, cabinet_id, subscription_status, trial_started_at, trial_ends_at) "
                + "VALUES (?, ?, 'TRIAL', ?, ?)"))
        {
            subscriptionStmt.setObject(1, subscriptionId);
            subscriptionStmt.setObject(2, cabinetId);
            subscriptionStmt.setTimestamp(3, Timestamp.from(now));
            subscriptionStmt.setTimestamp(4, Timestamp.from(trialEnd));
            subscriptionStmt.executeUpdate();
        }

        requireAttemptOwnership(conn, cabinetId, attemptId);
        // Activate cabinet - conditional on attempt and status
        try (PreparedStatement activateStmt = conn.prepareStatement(
            "UPDATE \"" + platformSchema
                + "\".cabinets SET provisioning_status = 'ACTIVE', provisioned_at = CURRENT_TIMESTAMP, "
                + "provisioning_attempt_id = ? WHERE id = ? AND provisioning_attempt_id = ? "
                + "AND provisioning_status = 'PROVISIONING'"))
        {
            activateStmt.setObject(1, attemptId);
            activateStmt.setObject(2, cabinetId);
            activateStmt.setObject(3, attemptId);
            int updated = activateStmt.executeUpdate();
            if (updated != 1)
            {
                throw new SQLException("Cabinet activation failed - attempt mismatch or status changed");
            }
        }
    }

    private void recordProvisioningFailure(UUID cabinetId, UUID attemptId, Exception cause)
    {
        // Never join the caller's Spring transaction or reuse the lock-owning physical session.
        try (Connection failureConn = openProvisioningConnection())
        {
            failureConn.setAutoCommit(false);
            try
            {
                try (PreparedStatement stmt = failureConn.prepareStatement(
                    "UPDATE \"" + platformSchema
                        + "\".cabinets SET provisioning_status = 'PROVISIONING_FAILED', "
                        + "last_provisioning_error = ? WHERE id = ? "
                        + "AND provisioning_attempt_id = ? AND provisioning_status = 'PROVISIONING'"))
                {
                    stmt.setString(1, sanitizeErrorCode(cause));
                    stmt.setObject(2, cabinetId);
                    stmt.setObject(3, attemptId);
                    // Zero rows means this attempt no longer owns PROVISIONING; leave its successor untouched.
                    stmt.executeUpdate();
                }
                failureConn.commit();
                LOG.debug("event=CABINET_PROVISIONING_FAILURE_RECORD_COMMITTED phase=FAILURE_RECORD");
            } catch (SQLException | RuntimeException recordFailure)
            {
                try
                {
                    failureConn.rollback();
                } catch (SQLException rollbackFailure)
                {
                    recordFailure.addSuppressed(rollbackFailure);
                }
                throw recordFailure;
            }
        } catch (Exception recordFailure)
        {
            // Retain the failure internally without attaching sensitive JDBC details to the public error.
            cause.addSuppressed(recordFailure);
            LOG.warn("event=CABINET_PROVISIONING_FAILURE_RECORD_FAILED phase=FAILURE_RECORD code={}",
                safeDiagnosticCode(recordFailure));
        }
    }

    /**
     * Restricts diagnostics to the finite vocabulary without changing public error sanitization.
     *
     * @param failure the internally retained failure, never included in a log
     * @return an allowlisted diagnostic code or the fixed fallback
     */
    private String safeDiagnosticCode(Exception failure)
    {
        String code = sanitizeErrorCode(failure);
        return SAFE_DIAGNOSTIC_CODES.contains(code) ? code : "PROVISIONING_FAILED";
    }

    private String sanitizeErrorCode(Exception e)
    {
        if (e instanceof CabinetProvisioningException)
        {
            return ((CabinetProvisioningException) e).code();
        }
        if (e.getMessage() != null && e.getMessage().contains("timeout"))
        {
            return "PROVISIONING_TIMEOUT";
        }
        if (e instanceof SQLException)
        {
            return "PROVISIONING_DATABASE_ERROR";
        }
        return "PROVISIONING_FAILED";
    }
}