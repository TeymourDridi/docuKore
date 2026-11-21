package com.docukore.identity.registration;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Server-side Gmail OAuth configuration; never expose this record through an API or logs.
 *
 * @param clientId OAuth client identifier used for token refresh requests
 * @param clientSecret OAuth client secret used for token refresh requests
 * @param refreshToken refresh token authorizing the central sender account
 * @param sender authenticated sender's bare email address
 * @param timeout HTTP request timeout, defaulting to ten seconds when null
 */
@ConfigurationProperties("docukore.mail.gmail")
public record GmailProperties(String clientId, String clientSecret, String refreshToken, String sender,
        Duration timeout)
{
    public GmailProperties
    {
        timeout = timeout == null ? Duration.ofSeconds(10) : timeout;
        if (timeout.compareTo(Duration.ofSeconds(1)) < 0 || timeout.compareTo(Duration.ofSeconds(30)) > 0)
        {
            throw new IllegalArgumentException("Invalid email delivery timeout");
        }
    }

    boolean configured()
    {
        return clientId != null && clientId.length() <= 512
                && clientId.matches("[A-Za-z0-9][A-Za-z0-9_-]*\\.apps\\.googleusercontent\\.com")
                && credential(clientSecret) && credential(refreshToken) && mailbox(sender);
    }

    private static boolean credential(String value)
    {
        return value != null && !value.isEmpty() && value.length() <= 4096
                && value.chars().allMatch(character -> character >= 33 && character <= 126);
    }

    /** Accept one bare ASCII mailbox, consistent with the backend signup boundary. */
    static boolean mailbox(String value)
    {
        if (value == null || value.length() > 255 || !value.matches(
                "[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?\\.[A-Za-z]{2,63}"))
        {
            return false;
        }
        int separator = value.indexOf('@');
        String local = value.substring(0, separator);
        if (local.length() > 64 || local.startsWith(".") || local.endsWith(".") || local.contains(".."))
        {
            return false;
        }
        for (String label : value.substring(separator + 1).split("\\.", -1))
        {
            if (label.isEmpty() || label.length() > 63 || label.startsWith("-") || label.endsWith("-"))
            {
                return false;
            }
        }
        return true;
    }

    @Override
    public String toString()
    {
        return "GmailProperties[redacted]";
    }
}
