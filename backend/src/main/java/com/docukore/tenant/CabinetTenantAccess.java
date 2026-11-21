package com.docukore.tenant;

import com.docukore.platform.PlatformProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.util.UUID;

@Component
public final class CabinetTenantAccess
{

    /** JdbcTemplate for platform schema access. */
    private final JdbcTemplate platformJdbc;
    /** Mapper for cabinet-to-schema resolution. */
    private final CabinetTenantSchemaMapper schemaMapper;
    /** Pre-formatted SQL query for cabinet availability checks. */
    private final String availabilityQuery;

    public CabinetTenantAccess(DataSource dataSource, CabinetTenantSchemaMapper schemaMapper,
            PlatformProperties platform)
    {
        this.platformJdbc = new JdbcTemplate(dataSource);
        this.schemaMapper = schemaMapper;
        this.availabilityQuery = """
            SELECT EXISTS (
                SELECT 1 FROM "%s".cabinets c
                WHERE c.id = ? AND c.provisioning_status = 'ACTIVE'
                    AND c.administrative_status = 'AVAILABLE' AND c.schema_name = ?
            )
            """.formatted(platform.schema());
    }

    // Call only after upstream authorization, before tenant persistence begins.
    TenantContext.Scope open(UUID cabinetId)
    {
        if (cabinetId == null || cabinetId.equals(new UUID(0, 0)))
        {
            throw new IllegalArgumentException("A valid cabinet UUID is required");
        }
        if (TenantContext.isPresent() || TransactionSynchronizationManager.isActualTransactionActive())
        {
            throw new IllegalStateException("Select tenant before starting persistence");
        }

        String resolvedSchema = schemaMapper.resolveSchemaName(cabinetId);
        Boolean available = platformJdbc.queryForObject(availabilityQuery, Boolean.class, cabinetId, resolvedSchema);
        if (!Boolean.TRUE.equals(available))
        {
            throw new IllegalStateException("Cabinet is unavailable or its schema mapping is invalid");
        }

        return TenantContext.open(cabinetId, resolvedSchema);
    }
}
