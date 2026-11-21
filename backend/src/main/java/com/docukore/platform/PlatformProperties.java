package com.docukore.platform;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Shared metadata namespace; tenant identifiers never select this schema.
 *
 * @param schema the platform schema name
 */
@ConfigurationProperties("docukore.platform")
public record PlatformProperties(String schema)
{

    /** Validates the trusted configuration before it can become an SQL identifier. */
    public PlatformProperties
    {
        schema = schema == null ? "public" : schema;
        if (!schema.matches("[a-z][a-z0-9_]{0,62}"))
        {
            throw new IllegalArgumentException("Invalid platform schema identifier");
        }
    }
}
