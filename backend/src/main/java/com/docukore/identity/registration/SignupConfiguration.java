package com.docukore.identity.registration;

import com.docukore.identity.DeploymentProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({SignupProperties.class, DeploymentProperties.class})
public class SignupConfiguration
{
    @Bean
    VerificationTokens verificationTokens()
    {
        return new VerificationTokens();
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "docukore.deployment.mode", havingValue = "LOCAL")
    static class LocalMailConfiguration
    {
        @Bean
        VerificationMailTransport verificationMailTransport()
        {
            // No provider properties, OAuth client or HTTP client exist in offline LOCAL mode.
            return ignored -> VerificationMailTransport.DeliveryResult.NOT_CONFIGURED;
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "docukore.deployment.mode", havingValue = "HOSTED", matchIfMissing = true)
    @EnableConfigurationProperties(MailProviderProperties.class)
    @Import({HostedMailConfiguration.ResendMailConfiguration.class, HostedMailConfiguration.GmailMailConfiguration.class})
    static class HostedMailConfiguration
    {
        @Bean
        VerificationMailTransport verificationMailTransport(MailProviderProperties selection,
                ObjectProvider<GmailProperties> gmail, ObjectProvider<ResendProperties> resend)
        {
            // Resolve the required selection before provider properties or any transport client.
            return switch (selection.provider())
            {
                case GMAIL_API ->
                {
                    var properties = gmail.getObject();
                    if (!properties.configured())
                    {
                        throw new IllegalArgumentException("HOSTED Gmail email configuration is incomplete");
                    }
                    yield new GmailApiVerificationMailTransport(properties);
                }
                case RESEND ->
                {
                    var properties = resend.getObject();
                    if (!properties.configured())
                    {
                        throw new IllegalArgumentException("HOSTED Resend email configuration is incomplete");
                    }
                    yield new ResendVerificationMailTransport(properties);
                }
            };
        }

        @ConditionalOnProperty(name = "docukore.mail.provider", havingValue = "RESEND")
        @EnableConfigurationProperties(ResendProperties.class)
        static class ResendMailConfiguration
        {
        }

        @ConditionalOnProperty(name = "docukore.mail.provider", havingValue = "GMAIL_API")
        @EnableConfigurationProperties(GmailProperties.class)
        static class GmailMailConfiguration
        {
        }
    }
}
