package com.docukore.identity.registration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;

import static org.assertj.core.api.Assertions.assertThat;

class SignupMailStartupTest
{
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(RequiresTransport.class, SignupConfiguration.class);

    @Test
    void hostedConsumerGetsExplicitProviderConfigurationErrorInsteadOfMissingMailBean()
    {
        context.run(application ->
        {
            assertThat(application).hasFailed();
            Throwable cause = application.getStartupFailure();
            while (cause.getCause() != null)
            {
                cause = cause.getCause();
            }
            assertThat(cause).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("HOSTED requires an explicit email provider");
        });
    }

    @Test
    void localConsumerStartsWithNoEmailConfiguration()
    {
        context.withPropertyValues("docukore.deployment.mode=LOCAL").run(application ->
                assertThat(application).hasNotFailed().hasSingleBean(RequiresTransport.class)
                        .hasSingleBean(VerificationMailTransport.class));
    }

    @Test
    void unknownHostedProviderFailsBindingInsteadOfReportingMissingTransport()
    {
        context.withPropertyValues("docukore.mail.provider=UNKNOWN").run(application ->
        {
            assertThat(application).hasFailed();
            assertThat(application.getStartupFailure())
                    .hasStackTraceContaining("Failed to bind properties under 'docukore.mail.provider'")
                    .hasRootCauseInstanceOf(IllegalArgumentException.class)
                    .hasStackTraceContaining("MailProviderProperties.Provider.UNKNOWN");
        });
    }

    @Test
    void scannedLocalConfigurationStartsWithoutBindingInvalidMailSettings()
    {
        new ApplicationContextRunner().withUserConfiguration(RequiresTransport.class, ScannedMailConfiguration.class)
                .withPropertyValues("docukore.deployment.mode=LOCAL", "docukore.mail.provider=UNKNOWN",
                        "docukore.mail.gmail.timeout=invalid", "docukore.mail.resend.timeout=-1s")
                .run(application -> assertThat(application).hasNotFailed().hasSingleBean(RequiresTransport.class)
                        .hasSingleBean(VerificationMailTransport.class).doesNotHaveBean(MailProviderProperties.class)
                        .doesNotHaveBean(GmailProperties.class).doesNotHaveBean(ResendProperties.class));
    }

    @Test
    void scannedHostedConfigurationSuppliesGmailToEagerConsumer()
    {
        new ApplicationContextRunner().withUserConfiguration(RequiresTransport.class, ScannedMailConfiguration.class)
                .withPropertyValues("docukore.deployment.mode=HOSTED", "docukore.mail.provider=GMAIL_API",
                        "docukore.mail.gmail.client-id=fictional.apps.googleusercontent.com",
                        "docukore.mail.gmail.client-secret=fictional-secret",
                        "docukore.mail.gmail.refresh-token=fictional-refresh",
                        "docukore.mail.gmail.sender=sender@example.test", "docukore.mail.resend.timeout=-1s")
                .run(application ->
                {
                    assertThat(application).hasNotFailed().hasSingleBean(RequiresTransport.class)
                            .hasSingleBean(VerificationMailTransport.class).doesNotHaveBean(ResendProperties.class);
                    assertThat(application.getBean(VerificationMailTransport.class))
                            .isInstanceOf(GmailApiVerificationMailTransport.class);
                });
    }

    @Test
    void scannedHostedConsumerGetsExplicitMissingProviderError()
    {
        new ApplicationContextRunner().withUserConfiguration(RequiresTransport.class, ScannedMailConfiguration.class)
                .withPropertyValues("docukore.deployment.mode=HOSTED")
                .run(application ->
                {
                    assertThat(application).hasFailed();
                    assertThat(application.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalArgumentException.class)
                            .hasStackTraceContaining("HOSTED requires an explicit email provider");
                });
    }

    @Configuration(proxyBeanMethods = false)
    @ComponentScan(basePackageClasses = SignupConfiguration.class, useDefaultFilters = false,
            includeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE,
                    classes = {SignupConfiguration.class, SignupConfiguration.LocalMailConfiguration.class,
                            SignupConfiguration.HostedMailConfiguration.class}))
    static class ScannedMailConfiguration
    {
    }

    static class RequiresTransport
    {
        RequiresTransport(VerificationMailTransport mail)
        {
        }
    }
}
