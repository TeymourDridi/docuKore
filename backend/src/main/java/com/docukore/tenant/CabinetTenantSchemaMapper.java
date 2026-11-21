package com.docukore.tenant;

import com.docukore.platform.PlatformProperties;
import org.hibernate.context.spi.TenantSchemaMapper;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.UUID;

public final class CabinetTenantSchemaMapper implements TenantSchemaMapper<UUID>
{

    /** JdbcTemplate for platform schema access. */
    private final JdbcTemplate platformJdbc;
    /** SQL query for cabinet schema identity resolution. */
    private final String identityQuery;

    public CabinetTenantSchemaMapper(DataSource dataSource)
    {
        this(dataSource, "public");
    }

    public CabinetTenantSchemaMapper(DataSource dataSource, String platformSchema)
    {
        this.platformJdbc = new JdbcTemplate(dataSource);
        String platform = '"' + new PlatformProperties(platformSchema).schema() + '"';
        this.identityQuery = """
            SELECT a.schema_name
            FROM %1$s.cabinets c
            JOIN %1$s.cabinet_schema_allocations a
                ON a.cabinet_id = c.id AND a.schema_name = c.schema_name
            JOIN pg_catalog.pg_namespace n ON n.nspname = a.schema_name
            WHERE c.id = ? AND (
                (a.allocation_kind = 'SEQUENTIAL' AND EXISTS (
                    SELECT 1 FROM %1$s.cabinet_schema_creation_receipts r
                    WHERE r.cabinet_id = a.cabinet_id AND r.reservation_id = a.reservation_id
                        AND r.schema_name = a.schema_name
                        AND r.namespace_oid = n.oid::bigint AND r.owner_oid = n.nspowner::bigint
                ))
                OR (a.allocation_kind = 'LEGACY' AND EXISTS (
                    SELECT 1 FROM %1$s.cabinet_legacy_schema_observations o
                    WHERE o.cabinet_id = a.cabinet_id AND o.reservation_id = a.reservation_id
                        AND o.schema_name = a.schema_name
                        AND o.namespace_oid = n.oid::bigint AND o.owner_oid = n.nspowner::bigint
                ))
            )
            """.formatted(platform);
    }

    @Override
    public String schemaName(UUID cabinetId)
    {
        // Hibernate may already hold the pool's only connection: use the captured binding only.
        TenantContext.requireMatches(cabinetId);
        return TenantContext.getSchemaName();
    }

    /**
     * Validates permanent platform/catalog evidence before opening tenant persistence.
     *
     * @param cabinetId the cabinet identifier
     * @return the resolved schema name
     */
    String resolveSchemaName(UUID cabinetId)
    {
        if (cabinetId == null || cabinetId.equals(new UUID(0, 0)))
        {
            throw new IllegalArgumentException("A valid cabinet UUID is required");
        }
        var schemas = platformJdbc.query(identityQuery, (row, index) -> row.getString("schema_name"), cabinetId);
        if (schemas.size() != 1)
        {
            throw new IllegalStateException("Cabinet schema identity is unavailable or invalid");
        }
        return schemas.getFirst();
    }
}
