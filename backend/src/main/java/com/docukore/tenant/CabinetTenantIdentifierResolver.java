package com.docukore.tenant;

import org.hibernate.context.spi.CurrentTenantIdentifierResolver;

import java.util.UUID;

public final class CabinetTenantIdentifierResolver implements CurrentTenantIdentifierResolver<UUID>
{

    @Override
    public UUID resolveCurrentTenantIdentifier()
    {
        return TenantContext.requireCabinetId();
    }

    @Override
    public boolean validateExistingCurrentSessions()
    {
        return true;
    }
}