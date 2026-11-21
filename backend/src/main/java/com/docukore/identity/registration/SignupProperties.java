package com.docukore.identity.registration;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;

@ConfigurationProperties("docukore.signup")
public record SignupProperties(URI appBaseUri, Duration verificationTtl, Duration resendCooldown)
{
    public SignupProperties
    {
        verificationTtl = verificationTtl == null ? Duration.ofHours(24) : verificationTtl;
        resendCooldown = resendCooldown == null ? Duration.ofMinutes(1) : resendCooldown;
        if (verificationTtl.compareTo(Duration.ofMinutes(1)) < 0
                || verificationTtl.compareTo(Duration.ofDays(7)) > 0
                || resendCooldown.compareTo(Duration.ofSeconds(1)) < 0
                || resendCooldown.compareTo(Duration.ofHours(24)) > 0)
        {
            throw new IllegalArgumentException("Invalid signup timing configuration");
        }
        if (appBaseUri != null && (appBaseUri.getHost() == null || appBaseUri.getRawUserInfo() != null
                || appBaseUri.getRawQuery() != null || appBaseUri.getRawFragment() != null))
        {
            throw new IllegalArgumentException("Signup application URI must be a trusted HTTPS base URI");
        }
    }

    URI verificationLink(String token)
    {
        if (appBaseUri == null)
        {
            throw new IllegalStateException("Verification delivery is not configured");
        }
        String base = appBaseUri.toASCIIString();
        // The fragment keeps proof out of HTTP access logs and Referer headers.
        return URI.create((base.endsWith("/") ? base : base + "/") + "verify-email#token=" + token);
    }

    @Override
    public String toString()
    {
        return "SignupProperties[redacted]";
    }
}
