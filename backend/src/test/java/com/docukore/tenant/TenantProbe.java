package com.docukore.tenant;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

@Entity
@Table(name = "tenant_probe")
public class TenantProbe
{

    /** Probe identifier. */
    @Id
    private UUID id;

    /** Marker value for testing. */
    private String marker;

    protected TenantProbe()
    {
    }

    TenantProbe(UUID id, String marker)
    {
        this.id = id;
        this.marker = marker;
    }

    String marker()
    {
        return marker;
    }

    void marker(String marker)
    {
        this.marker = marker;
    }
}