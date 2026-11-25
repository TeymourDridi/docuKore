package com.docukore.cabinet.access;

import com.docukore.cabinet.membership.CabinetMembership;
import com.docukore.platform.PlatformProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class CabinetMembershipRepository
{

    /** JdbcTemplate for platform schema access. */
    private final JdbcTemplate platformJdbc;
    /** Pre-formatted SQL query for active membership checks. */
    private final String activeMembershipQuery;
    /** Trusted audit role query in the configured platform namespace. */
    private final String activeRoleQuery;
    /** Membership details used for platform authorization without a tenant session. */
    private final String activeMemberQuery;
    /** At most two available cabinets, sufficient to reject ambiguous selection. */
    private final String availableCabinetsQuery;

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
        this.activeRoleQuery = """
            SELECT CASE WHEN m.membership_role = 'OWNER' THEN 'OWNER'
                WHEN m.staff_type IN ('DOCTOR', 'SECRETARY') THEN m.staff_type ELSE 'MEMBER' END
            FROM %1$s.cabinet_memberships m
            JOIN %1$s.cabinets c ON c.id = m.cabinet_id
            WHERE c.id = ? AND c.provisioning_status = 'ACTIVE' AND c.administrative_status = 'AVAILABLE'
                AND m.user_id = ? AND m.status = 'ACTIVE' AND m.revoked_at IS NULL
            """.formatted('"' + platform.schema() + '"');
        this.activeMemberQuery = """
            SELECT id, cabinet_id, user_id, membership_role, staff_type, status
            FROM %s.cabinet_memberships
            WHERE cabinet_id = ? AND user_id = ? AND status = 'ACTIVE' AND revoked_at IS NULL
            """.formatted('"' + platform.schema() + '"');
        this.availableCabinetsQuery = """
            SELECT DISTINCT c.id FROM %1$s.cabinet_memberships m
            JOIN %1$s.cabinets c ON c.id = m.cabinet_id
            WHERE m.user_id = ? AND m.status = 'ACTIVE' AND m.revoked_at IS NULL
                AND c.provisioning_status = 'ACTIVE' AND c.administrative_status = 'AVAILABLE'
            ORDER BY c.id LIMIT 2
            """.formatted('"' + platform.schema() + '"');
    }

    /**
     * Discover a sole available cabinet without accepting client-controlled selection.
     * Two results signal ambiguity; operation authorization remains the access gateway's responsibility.
     *
     * @param userId the authenticated account
     * @return zero, one or two distinct available cabinet identifiers
     */
    public List<UUID> findAvailableCabinets(UUID userId)
    {
        return platformJdbc.query(availableCabinetsQuery, (row, index) -> row.getObject(1, UUID.class), userId);
    }

    public Optional<CabinetMembership> findActiveMembership(UUID cabinetId, UUID userId)
    {
        return platformJdbc.query(activeMemberQuery, (row, index) -> new CabinetMembership(
            row.getObject("id", UUID.class), row.getObject("cabinet_id", UUID.class),
            row.getObject("user_id", UUID.class), row.getString("membership_role"),
            row.getString("staff_type"), row.getString("status")), cabinetId, userId).stream().findFirst();
    }

    /**
     * Resolve only an active member's server-maintained cabinet role for audit attribution.
     *
     * @param cabinetId the active cabinet
     * @param userId the authenticated account
     * @return the trusted role, or empty when access is inactive
     */
    public Optional<String> findActiveRole(UUID cabinetId, UUID userId)
    {
        return platformJdbc.query(activeRoleQuery, (row, index) -> row.getString(1), cabinetId, userId)
            .stream().findFirst();
    }

    public boolean hasActiveMembership(UUID cabinetId, UUID userId)
    {
        return Boolean.TRUE.equals(platformJdbc.queryForObject(
                activeMembershipQuery, Boolean.class, cabinetId, userId));
    }
}
