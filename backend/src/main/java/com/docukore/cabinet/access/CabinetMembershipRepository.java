package com.docukore.cabinet.access;

import com.docukore.platform.PlatformProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.util.UUID;

@Repository
public class CabinetMembershipRepository
{

    /** JdbcTemplate for platform schema access. */
    private final JdbcTemplate platformJdbc;
    /** Pre-formatted SQL query for active membership checks. */
    private final String activeMembershipQuery;

    public CabinetMembershipRepository(DataSource dataSource, PlatformProperties platform)
    {
        this.platformJdbc = new JdbcTemplate(dataSource);
        this.activeMembershipQuery = """
            SELECT EXISTS (
                SELECT 1 FROM %1$s.cabinet_memberships m
                JOIN %1$s.cabinets c ON c.id = m.cabinet_id
                WHERE c.id = ? AND c.provisioning_status = 'ACTIVE'
                    AND c.administrative_status = 'AVAILABLE'
                    AND m.user_id = ? AND m.status = 'ACTIVE' AND m.revoked_at IS NULL
            )
            """.formatted('"' + platform.schema() + '"');
    }

    public boolean hasActiveMembership(UUID cabinetId, UUID userId)
    {
        return Boolean.TRUE.equals(platformJdbc.queryForObject(
                activeMembershipQuery, Boolean.class, cabinetId, userId));
    }
}
