package com.docukore.tenant;

import org.hibernate.context.spi.CurrentTenantIdentifierResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

public final class CabinetTenantIdentifierResolver implements CurrentTenantIdentifierResolver<UUID>
{

    private static final Logger log = LoggerFactory.getLogger(CabinetTenantIdentifierResolver.class);

    @Override
    public UUID resolveCurrentTenantIdentifier()
    {
        return TenantContext.requireCabinetId();
    }

    @Override
    public boolean validateExistingCurrentSessions()
    {
        log.debug("Validating existing Hibernate sessions - allowing concurrent access");
        return true;
    }
}