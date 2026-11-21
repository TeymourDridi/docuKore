package com.docukore.identity;

import org.springframework.jdbc.core.JdbcTemplate;
import com.docukore.platform.PlatformProperties;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

public class AccountRepository
{

    /** JdbcTemplate for the schema-specific data source. */
    private final JdbcTemplate jdbc;

    /** Database schema name (platform or tenant). */
    private final String schema;

    public AccountRepository(DataSource dataSource)
    {
        this(dataSource, "public");
    }

    public AccountRepository(DataSource dataSource, String schema)
    {
        this.jdbc = new JdbcTemplate(dataSource);
        this.schema = new PlatformProperties(schema).schema();
    }

    public Optional<Account> find(String username)
    {
        return jdbc.query("""
            SELECT id, username, full_name, email, password_hash, account_status,
                   deployment_mode, activated_at
            FROM %s.users WHERE username_normalized = ?
            """.formatted(schema), (rows, index) -> new Account(
                rows.getObject("id", UUID.class),
                rows.getString("username"),
                rows.getString("full_name"),
                rows.getString("email"),
                rows.getString("password_hash"),
                rows.getString("account_status"),
                rows.getString("deployment_mode"),
                rows.getTimestamp("activated_at") == null ? null
                    : rows.getTimestamp("activated_at").toInstant()),
            normalize(username)).stream().findFirst();
    }

    public boolean setupAvailable()
    {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT completed_at IS NULL AND NOT EXISTS (SELECT 1 FROM %s.users)
            FROM %s.installation_setup WHERE id = 1
            """.formatted(schema, schema), Boolean.class));
    }

    public boolean lockSetupAvailable()
    {
        jdbc.queryForObject(
            "SELECT id FROM %s.installation_setup WHERE id = 1 FOR UPDATE".formatted(schema),
            Integer.class);
        return setupAvailable();
    }

    public void createFirstOwner(String username, String fullName, String hash)
    {
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO %s.users (id, username, username_normalized, password_hash, full_name,
                                  deployment_mode, created_at, activated_at, account_status)
            VALUES (?, ?, ?, ?, ?, 'local', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'ACTIVE')
            """.formatted(schema), id, username, normalize(username), hash, fullName);
        jdbc.update(
            "UPDATE %s.installation_setup SET first_owner_id = ?, completed_at = CURRENT_TIMESTAMP WHERE id = 1"
                .formatted(schema),
            id);
    }

    @org.springframework.transaction.annotation.Transactional(transactionManager = "platformTransactionManager")
    public void recordLogin(String username)
    {
        jdbc.update(
            "UPDATE %s.users SET last_login_at = CURRENT_TIMESTAMP WHERE username_normalized = ?"
                .formatted(schema),
            normalize(username));
    }

    static String normalize(String username)
    {
        return username == null ? "" : username.strip().toLowerCase(Locale.ROOT);
    }

    public record Account(UUID id, String username, String fullName, String email, String passwordHash,
            String status, String mode, Instant activatedAt)
    {

        boolean loginAllowed(DeploymentProperties deployment)
        {
            return "ACTIVE".equals(status) && activatedAt != null
                    && (deployment.local() ? "local".equals(mode)
                    : "hosted".equals(mode) && email != null && !email.isBlank());
        }

        @Override
        public String toString()
        {
            return "Account[redacted]";
        }
    }
}