package com.docukore.identity.registration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ClassPathResource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SignupMailConfigurationTest
{
    /** Isolated context runner for deployment-specific mail configuration and property binding. */
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(SignupConfiguration.class);

    @Test
    void localStartsWithoutAnyMailConfiguration()
    {
        context.withPropertyValues("docukore.deployment.mode=LOCAL").run(application ->
        {
            assertThat(application).hasNotFailed().hasSingleBean(VerificationMailTransport.class);
            assertThat(application.getBean(VerificationMailTransport.class).send(null))
                    .isEqualTo(VerificationMailTransport.DeliveryResult.NOT_CONFIGURED);
            assertThat(application).doesNotHaveBean(ResendProperties.class);
        });
    }

    @Test
    void localIgnoresInvalidMailSettingsWithoutInitializingProvider()
    {
        context.withPropertyValues("docukore.deployment.mode=LOCAL", "docukore.mail.provider=UNKNOWN",
                "docukore.mail.resend.timeout=-1s", "docukore.mail.gmail.timeout=invalid")
                .run(application ->
                {
                    assertThat(application).hasNotFailed().doesNotHaveBean(ResendProperties.class);
                    assertThat(application.getBean(VerificationMailTransport.class).send(null))
                            .isEqualTo(VerificationMailTransport.DeliveryResult.NOT_CONFIGURED);
                });
    }

    @Test
    void localDoesNotBindGmailProperties()
    {
        context.withPropertyValues("docukore.deployment.mode=LOCAL", "docukore.mail.provider=GMAIL_API")
                .run(application -> assertThat(application).hasNotFailed().doesNotHaveBean(GmailProperties.class)
                        .doesNotHaveBean(MailProviderProperties.class));
    }

    @Test
    void hostedSelectsGmailWithoutBindingResendOrRequiringAppBaseYet()
    {
        context.withPropertyValues("docukore.mail.provider=GMAIL_API",
                "docukore.mail.gmail.client-id=fictional.apps.googleusercontent.com",
                "docukore.mail.gmail.client-secret=fictional-secret",
                "docukore.mail.gmail.refresh-token=fictional-refresh",
                "docukore.mail.gmail.sender=sender@example.test", "docukore.mail.resend.timeout=-1s")
                .run(application ->
                {
                    assertThat(application).hasNotFailed().hasSingleBean(VerificationMailTransport.class)
                            .doesNotHaveBean(ResendProperties.class);
                    assertThat(application.getBean(VerificationMailTransport.class))
                            .isInstanceOf(GmailApiVerificationMailTransport.class);
                    assertThat(application.getBean(SignupProperties.class).appBaseUri()).isNull();
                });
    }

    @Test
    void approvedUnderscoreEnvironmentKeysBindThroughActualHostedYaml() throws Exception
    {
        var hostedYaml = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml")).getLast();
        context.withInitializer(application ->
        {
            application.getEnvironment().getPropertySources().addFirst(hostedYaml);
            application.getEnvironment().getPropertySources().addFirst(new SystemEnvironmentPropertySource(
                    "fictionalEnvironment", Map.of(
                            "DOCUKORE_MAIL_PROVIDER", "GMAIL_API",
                            "DOCUKORE_MAIL_GMAIL_CLIENT_ID", "fictional.apps.googleusercontent.com",
                            "DOCUKORE_MAIL_GMAIL_CLIENT_SECRET", "fictional-secret",
                            "DOCUKORE_MAIL_GMAIL_REFRESH_TOKEN", "fictional-refresh",
                            "DOCUKORE_MAIL_GMAIL_SENDER", "sender@example.test")));
        }).run(application ->
        {
            assertThat(application).hasNotFailed().hasSingleBean(GmailProperties.class);
            var properties = application.getBean(GmailProperties.class);
            assertThat(properties.clientId()).isEqualTo("fictional.apps.googleusercontent.com");
            assertThat(properties.clientSecret()).isEqualTo("fictional-secret");
            assertThat(properties.refreshToken()).isEqualTo("fictional-refresh");
            assertThat(properties.sender()).isEqualTo("sender@example.test");
        });
    }

    @Test
    void hostedRejectsIncompleteGmailConfiguration()
    {
        context.withPropertyValues("docukore.mail.provider=GMAIL_API")
                .run(application -> assertThat(application).hasFailed());
    }

    @Test
    void hostedRequiresExplicitProvider()
    {
        context.run(application -> assertThat(application).hasFailed());
        context.withPropertyValues("docukore.deployment.mode=HOSTED", "docukore.mail.provider=UNKNOWN")
                .run(application -> assertThat(application).hasFailed());
    }

    @Test
    void hostedRejectsIncompleteSelectedResendConfiguration()
    {
        context.withPropertyValues("docukore.mail.provider=RESEND")
                .run(application -> assertThat(application).hasFailed());
    }

    @Test
    void hostedSelectsConfiguredResendWithoutNeedingGmail()
    {
        context.withPropertyValues("docukore.mail.provider=RESEND", "docukore.mail.resend.api-key=fictional-key",
                "docukore.mail.resend.sender=sender@example.test", "docukore.mail.gmail.timeout=invalid")
                .run(application ->
                {
                    assertThat(application).hasNotFailed().hasSingleBean(VerificationMailTransport.class);
                    assertThat(application.getBean(VerificationMailTransport.class))
                            .isInstanceOf(ResendVerificationMailTransport.class);
                });
    }
}
