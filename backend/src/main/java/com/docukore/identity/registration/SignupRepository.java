package com.docukore.identity.registration;

import com.docukore.platform.PlatformProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public class SignupRepository
{
    /** JdbcTemplate for platform schema access. */
    private final JdbcTemplate jdbc;
    /** Platform schema name with quotes. */
    private final String schema;

    public SignupRepository(DataSource dataSource, PlatformProperties platform)
    {
        this.jdbc = new JdbcTemplate(dataSource);
        this.schema = "\"" + platform.schema() + "\"";
    }

    void lockRequest(UUID key)
    {
        jdbc.query("SELECT pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(?, 0))",
                (rows, index) -> true, "signup:" + key);
    }

    Optional<Registration> findByKey(UUID key)
    {
        return registrationQuery("r.idempotency_key = ?", key, false);
    }

    Optional<Registration> lockRegistration(UUID id)
    {
        // Account lock first, consistent with final provisioning eligibility checks.
        jdbc.query("SELECT u.id FROM " + schema + ".users u JOIN " + schema
                + ".initial_cabinet_registrations r ON r.account_id = u.id WHERE r.id = ? FOR UPDATE OF u",
                (rows, index) -> rows.getObject(1, UUID.class), id);
        return registrationQuery("r.id = ?", id, true);
    }

    Optional<Registration> findPendingByEmail(String email)
    {
        return registrationQuery("u.email_normalized = ? AND u.deployment_mode = 'hosted'", email, false);
    }

    boolean identityExists(String username, String email)
    {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS (SELECT 1 FROM %s.users WHERE username_normalized = ?
                OR (? IS NOT NULL AND email_normalized = ? AND deployment_mode = 'hosted'))
            """.formatted(schema), Boolean.class, username, email, email));
    }

    Registration insert(CanonicalSignup signup, String passwordHash, Instant now)
    {
        UUID accountId = UUID.randomUUID();
        UUID registrationId = UUID.randomUUID();
        boolean local = "local".equals(signup.mode());
        jdbc.update("""
            INSERT INTO %s.users (id, username, username_normalized, email, email_normalized,
                password_hash, full_name, deployment_mode, created_at, activated_at, account_status)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.formatted(schema), accountId, signup.username(), signup.username(), signup.email(),
                local ? null : signup.email(), passwordHash, signup.fullName(), signup.mode(), Timestamp.from(now),
                local ? Timestamp.from(now) : null, local ? "ACTIVE" : "PENDING_ACTIVATION");
        jdbc.update("""
            INSERT INTO %s.initial_cabinet_registrations (id, account_id, idempotency_key, cabinet_name, created_at)
            VALUES (?, ?, ?, ?, ?)
            """.formatted(schema), registrationId, accountId, signup.key(), signup.cabinetName(), Timestamp.from(now));
        return lockRegistration(registrationId).orElseThrow();
    }

    Optional<UUID> registrationForToken(String hash)
    {
        return jdbc.query("SELECT registration_id FROM " + schema + ".email_verification_tokens WHERE token_hash = ?",
                (rows, index) -> rows.getObject(1, UUID.class), hash).stream().findFirst();
    }

    boolean consumeToken(UUID registrationId, String hash, Instant now)
    {
        return jdbc.update("""
            UPDATE %s.email_verification_tokens SET consumed_at = ?
            WHERE registration_id = ? AND token_hash = ? AND consumed_at IS NULL AND expires_at > ?
            """.formatted(schema), Timestamp.from(now), registrationId, hash, Timestamp.from(now)) == 1;
    }

    void invalidateTokens(UUID registrationId, Instant now)
    {
        jdbc.update("UPDATE " + schema + ".email_verification_tokens SET consumed_at = ?"
                + " WHERE registration_id = ? AND consumed_at IS NULL", Timestamp.from(now), registrationId);
    }

    void activate(UUID accountId, Instant now)
    {
        if (jdbc.update("""
            UPDATE %s.users SET account_status = 'ACTIVE', activated_at = ?, email_verified_at = ?
            WHERE id = ? AND account_status = 'PENDING_ACTIVATION' AND deployment_mode = 'hosted'
            """.formatted(schema), Timestamp.from(now), Timestamp.from(now), accountId) != 1)
        {
            throw new IllegalStateException("Registration is not eligible");
        }
    }

    Optional<DeliveryState> lastDelivery(UUID registrationId)
    {
        return jdbc.query("""
            SELECT last_delivery_at, last_delivery_error FROM %s.email_verification_tokens
            WHERE registration_id = ? ORDER BY created_at DESC, id DESC LIMIT 1
            """.formatted(schema), (rows, index) -> new DeliveryState(
                instant(rows, "last_delivery_at"), rows.getString("last_delivery_error")), registrationId)
                .stream().findFirst();
    }

    UUID createToken(UUID registrationId, String hash, Instant now, Instant expiresAt)
    {
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO %s.email_verification_tokens (id, registration_id, token_hash, created_at,
                expires_at, delivery_attempts, last_delivery_at, last_delivery_error)
            VALUES (?, ?, ?, ?, ?, 1, ?, 'DELIVERY_PENDING')
            """.formatted(schema), id, registrationId, hash, Timestamp.from(now),
                Timestamp.from(expiresAt), Timestamp.from(now));
        return id;
    }

    void recordDelivery(UUID tokenId, VerificationMailTransport.DeliveryResult result)
    {
        jdbc.update("UPDATE " + schema + ".email_verification_tokens SET last_delivery_error = ? WHERE id = ?",
                result == VerificationMailTransport.DeliveryResult.DELIVERED ? null : result.name(), tokenId);
    }

    private Optional<Registration> registrationQuery(String predicate, Object value, boolean lock)
    {
        return jdbc.query("""
            SELECT r.id, r.account_id, r.idempotency_key, r.cabinet_name, r.cabinet_id,
                u.username_normalized, u.email, u.full_name, u.password_hash, u.account_status,
                u.deployment_mode, u.activated_at, u.email_verified_at
            FROM %s.initial_cabinet_registrations r JOIN %s.users u ON u.id = r.account_id
            WHERE %s
            """.formatted(schema, schema, predicate) + (lock ? " FOR UPDATE OF r" : ""),
                (rows, index) -> new Registration(rows.getObject("id", UUID.class),
                    rows.getObject("account_id", UUID.class), rows.getObject("idempotency_key", UUID.class),
                    rows.getString("username_normalized"), rows.getString("email"), rows.getString("full_name"),
                    rows.getString("cabinet_name"), rows.getString("password_hash"),
                    rows.getString("account_status"), rows.getString("deployment_mode"),
                    instant(rows, "activated_at"), instant(rows, "email_verified_at"),
                    rows.getObject("cabinet_id", UUID.class)), value).stream().findFirst();
    }

    private static Instant instant(ResultSet rows, String column) throws SQLException
    {
        Timestamp value = rows.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    record CanonicalSignup(String username, String email, String fullName, String cabinetName, String mode, UUID key)
    {
        @Override
        public String toString()
        {
            return "CanonicalSignup[redacted]";
        }
    }

    record Registration(UUID id, UUID accountId, UUID key, String username, String email,
            String fullName, String cabinetName, String passwordHash, String status, String mode,
            Instant activatedAt, Instant verifiedAt, UUID cabinetId)
    {
        boolean pendingHosted()
        {
            return "hosted".equals(mode) && "PENDING_ACTIVATION".equals(status)
                    && activatedAt == null && verifiedAt == null && cabinetId == null;
        }

        boolean eligible(String expectedMode)
        {
            return expectedMode.equals(mode) && "ACTIVE".equals(status) && activatedAt != null
                    && ("local".equals(mode) || verifiedAt != null);
        }

        @Override
        public String toString()
        {
            return "Registration[redacted]";
        }
    }

    record DeliveryState(Instant lastAttemptAt, String error)
    {
    }
}
