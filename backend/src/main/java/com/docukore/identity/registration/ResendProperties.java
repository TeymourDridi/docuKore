package com.docukore.identity.registration;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("docukore.mail.resend")
public record ResendProperties(String apiKey, String sender, Duration timeout)
{
    public ResendProperties
    {
        timeout = timeout == null ? Duration.ofSeconds(10) : timeout;
        if (timeout.compareTo(Duration.ofSeconds(1)) < 0 || timeout.compareTo(Duration.ofSeconds(30)) > 0)
        {
            throw new IllegalArgumentException("Invalid email delivery timeout");
        }
    }

    boolean configured()
    {
        return apiKey != null && !apiKey.isBlank() && apiKey.length() <= 512
                && apiKey.chars().noneMatch(Character::isISOControl)
                && sender != null && !sender.isBlank() && sender.length() <= 320
                && sender.chars().noneMatch(Character::isISOControl);
    }

    @Override
    public String toString()
    {
        return "ResendProperties[redacted]";
    }
}
