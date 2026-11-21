package com.docukore.identity.registration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GmailPropertiesTest
{
    /** Fictional valid OAuth client identifier for configuration validation. */
    private static final String CLIENT_ID = "1234567890-fictional.apps.googleusercontent.com";
    /** Fictional valid client secret for credential validation and redaction checks. */
    private static final String CLIENT_SECRET = "fictional-client-secret";
    /** Fictional valid refresh token for credential validation and redaction checks. */
    private static final String REFRESH_TOKEN = "fictional-refresh-token";
    /** Fictional valid authenticated sender mailbox for configuration validation. */
    private static final String SENDER = "sender@example.test";

    @Test
    void configurationPrefixAndDefaultTimeoutFollowMailConventions()
    {
        var properties = new GmailProperties(CLIENT_ID, CLIENT_SECRET, REFRESH_TOKEN, SENDER, null);

        assertThat(GmailProperties.class.getAnnotation(ConfigurationProperties.class).value())
                .isEqualTo("docukore.mail.gmail");
        assertThat(properties.timeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(properties.configured()).isTrue();
    }

    @Test
    void timeoutIsBoundedAndItsErrorDoesNotExposeCredentials()
    {
        for (Duration timeout : new Duration[]{Duration.ZERO, Duration.ofMillis(999), Duration.ofSeconds(31)})
        {
            assertThatThrownBy(() -> new GmailProperties(CLIENT_ID, CLIENT_SECRET, REFRESH_TOKEN, SENDER, timeout))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Invalid email delivery timeout");
        }
        assertThat(new GmailProperties(CLIENT_ID, CLIENT_SECRET, REFRESH_TOKEN, SENDER,
                Duration.ofSeconds(1)).configured()).isTrue();
        assertThat(new GmailProperties(CLIENT_ID, CLIENT_SECRET, REFRESH_TOKEN, SENDER,
                Duration.ofSeconds(30)).configured()).isTrue();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "not-a-google-client", "client.apps.googleusercontent.com\r\nInjected: value"})
    void missingOrMalformedClientIdIsNotConfigured(String clientId)
    {
        assertThat(new GmailProperties(clientId, CLIENT_SECRET, REFRESH_TOKEN, SENDER, null).configured()).isFalse();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "secret\nheader", "embedded whitespace"})
    void missingOrUnsafeCredentialsAreNotConfigured(String credential)
    {
        assertThat(new GmailProperties(CLIENT_ID, credential, REFRESH_TOKEN, SENDER, null).configured()).isFalse();
        assertThat(new GmailProperties(CLIENT_ID, CLIENT_SECRET, credential, SENDER, null).configured()).isFalse();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "not-an-email", "sender@example.test\r\nBcc: other@example.test",
            "Sender <sender@example.test>", "one@example.test,two@example.test", "a@-example.test",
            ".sender@example.test", "sender..name@example.test"})
    void onlyASingleSafeMailboxCanBeTheAuthenticatedSender(String sender)
    {
        assertThat(new GmailProperties(CLIENT_ID, CLIENT_SECRET, REFRESH_TOKEN, sender, null).configured()).isFalse();
    }

    @Test
    void boundsCredentialsAndRedactsEveryPropertyInToString()
    {
        assertThat(new GmailProperties(CLIENT_ID, "x".repeat(4097), REFRESH_TOKEN, SENDER, null).configured())
                .isFalse();
        assertThat(new GmailProperties(CLIENT_ID, CLIENT_SECRET, "x".repeat(4097), SENDER, null).configured())
                .isFalse();
        assertThat(new GmailProperties(CLIENT_ID, CLIENT_SECRET, REFRESH_TOKEN, SENDER, null).toString())
                .isEqualTo("GmailProperties[redacted]")
                .doesNotContain(CLIENT_ID, CLIENT_SECRET, REFRESH_TOKEN, SENDER);
    }
}
