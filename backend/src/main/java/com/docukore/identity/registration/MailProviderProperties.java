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
        GMAIL_API,
        RESEND
    }
}
