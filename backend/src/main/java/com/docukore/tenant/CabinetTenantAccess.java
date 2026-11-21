package com.docukore.tenant;

import com.docukore.platform.PlatformProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.util.UUID;

@Component
public final class CabinetTenantAccess
{

    /**
     * Logger for this class.
     */
    private static final Logger LOG = LoggerFactory.getLogger(CabinetTenantAccess.class);

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
        String platformSchema = '"' + new PlatformProperties(platform.schema()).schema() + '"';
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
            LOG.error("Invalid cabinet UUID: {}", cabinetId);
            throw new IllegalArgumentException("A valid cabinet UUID is required");
        }
        if (TenantContext.isPresent() || TransactionSynchronizationManager.isActualTransactionActive())
        {
            LOG.error("Cannot open tenant context - already present or transaction active");
            throw new IllegalStateException("Select tenant before starting persistence");
        }

        String resolvedSchema = schemaMapper.resolveSchemaName(cabinetId);
        LOG.debug("Resolving tenant context for cabinet {} -> schema {}", cabinetId, resolvedSchema);

        Boolean available = platformJdbc.queryForObject(availabilityQuery, Boolean.class, cabinetId, resolvedSchema);
        if (!Boolean.TRUE.equals(available))
        {
            LOG.warn("Cabinet {} is unavailable - schema: {}", cabinetId, resolvedSchema);
            throw new IllegalStateException("Cabinet is unavailable or its schema mapping is invalid");
        }

        LOG.info("Tenant context opened successfully - cabinet: {}, schema: {}", cabinetId, resolvedSchema);
        return TenantContext.open(cabinetId, resolvedSchema);
    }
}