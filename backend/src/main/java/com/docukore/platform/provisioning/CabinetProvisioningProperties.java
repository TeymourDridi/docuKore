package com.docukore.platform.provisioning;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Locale;
import java.util.Set;

/**
 * External, capability-local credentials and bounded provisioning timeouts.
 *
 * @param url              the provisioning database URL
 * @param username         the provisioning database username
 * @param password         the provisioning database password
 * @param lockTimeout      the lock acquisition timeout
 * @param connectTimeout   the connection establishment timeout
 * @param statementTimeout the statement execution timeout
 * @param networkTimeout   the network operation timeout
 */
@ConfigurationProperties("docukore.provisioning")
public record CabinetProvisioningProperties(String url, String username, String password,
        Duration lockTimeout, Duration connectTimeout, Duration statementTimeout, Duration networkTimeout)
{

    /** Only TLS options may be supplied in the URL; connection bounds cannot be overridden there. */
    private static final Set<String> URL_OPTIONS = Set.of("sslmode", "sslrootcert", "sslcert", "sslkey");

    /** Defaults do not enable provisioning or establish a connection during application startup. */
    public CabinetProvisioningProperties
    {
        lockTimeout = lockTimeout == null ? Duration.ofSeconds(10) : lockTimeout;
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(5) : connectTimeout;
        statementTimeout = statementTimeout == null ? Duration.ofSeconds(60) : statementTimeout;
        networkTimeout = networkTimeout == null ? Duration.ofSeconds(90) : networkTimeout;
    }

    /** Validates only when the capability is called; invalid settings do not disable authentication. */
    void requireEnabled()
    {
        if (url == null || url.isBlank() || username == null || username.isBlank()
                || password == null || password.isBlank())
        {
            throw new CabinetProvisioningException("PROVISIONING_DISABLED");
        }
        if (!url.startsWith("jdbc:postgresql://") || url.contains("\n") || url.contains("\r")
                || !bounded(lockTimeout, 60) || !bounded(connectTimeout, 30)
                || !bounded(statementTimeout, 600) || !bounded(networkTimeout, 900)
                || networkTimeout.compareTo(statementTimeout) < 0)
        {
            throw new CabinetProvisioningException("PROVISIONING_CONFIGURATION");
        }
        int query = url.indexOf('?');
        if (query >= 0)
        {
            for (String option : url.substring(query + 1).split("&"))
            {
                String key = option.split("=", 2)[0].toLowerCase(Locale.ROOT);
                if (!URL_OPTIONS.contains(key))
                {
                    throw new CabinetProvisioningException("PROVISIONING_CONFIGURATION");
                }
            }
        }
    }

    /** Returns whether the configured duration is positive and finite within the supported bound. */
    private static boolean bounded(Duration duration, int maximumSeconds)
    {
        return duration.compareTo(Duration.ofSeconds(1)) >= 0
            && duration.compareTo(Duration.ofSeconds(maximumSeconds)) <= 0;
    }

    /** Never reveals credentials, URL parameters or filesystem paths to logs. */
    @Override
    public String toString()
    {
        return "CabinetProvisioningProperties[credentials=REDACTED]";
    }
}
