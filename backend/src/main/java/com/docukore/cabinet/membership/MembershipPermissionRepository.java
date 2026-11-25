package com.docukore.cabinet.membership;

import com.docukore.platform.PlatformProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Reads active permission grants from the configured platform schema without opening a tenant session. */
@Repository
public class MembershipPermissionRepository
{
    /** JDBC access to the shared platform datasource. */
    private final JdbcTemplate jdbc;
    /** Schema-qualified selection of active grants. */
    private final String activeGrants;
    /** Schema-qualified check for any active grant. */
    private final String activeGrantExists;

    public MembershipPermissionRepository(DataSource dataSource, PlatformProperties platform)
    {
        this.jdbc = new JdbcTemplate(dataSource);
        String table = '"' + platform.schema() + '"' + ".membership_permissions";
        this.activeGrants = "SELECT id, membership_id, permission_code, granted_at, granted_by FROM " + table
            + " WHERE membership_id = ? AND revoked_at IS NULL";
        this.activeGrantExists = "SELECT EXISTS (SELECT 1 FROM " + table
            + " WHERE membership_id = ? AND revoked_at IS NULL)";
    }

    public List<MembershipPermission> findActivePermissions(UUID membershipId)
    {
        return jdbc.query(activeGrants, (row, index) -> new MembershipPermission(
            row.getObject("id", UUID.class), row.getObject("membership_id", UUID.class),
            row.getString("permission_code"), row.getObject("granted_at", OffsetDateTime.class),
            row.getObject("granted_by", UUID.class)), membershipId);
    }

    public Optional<MembershipPermission> findActivePermission(UUID membershipId, String permissionCode)
    {
        return jdbc.query(activeGrants + " AND permission_code = ?", (row, index) -> new MembershipPermission(
            row.getObject("id", UUID.class), row.getObject("membership_id", UUID.class),
            row.getString("permission_code"), row.getObject("granted_at", OffsetDateTime.class),
            row.getObject("granted_by", UUID.class)), membershipId, permissionCode).stream().findFirst();
    }

    public boolean hasActivePermissions(UUID membershipId)
    {
        return Boolean.TRUE.equals(jdbc.queryForObject(activeGrantExists, Boolean.class, membershipId));
    }
}
