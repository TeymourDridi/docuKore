package com.docukore.identity.registration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("docukore.mail")
public record MailProviderProperties(Provider provider)
{
    public MailProviderProperties
    {
        if (provider == null)
        {
            throw new IllegalArgumentException("HOSTED requires an explicit email provider: GMAIL_API or RESEND");
        }
    }

    public enum Provider
    {
        /** Sends verification emails through the Gmail API. */
        GMAIL_API,
        /** Sends verification emails through Resend. */
        RESEND
    }
}
