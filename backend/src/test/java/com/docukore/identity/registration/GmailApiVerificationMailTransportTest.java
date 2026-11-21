package com.docukore.identity.registration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;

import static com.docukore.identity.registration.VerificationMailTransport.DeliveryResult.DELIVERED;
import static com.docukore.identity.registration.VerificationMailTransport.DeliveryResult.DELIVERY_INTERRUPTED;
import static com.docukore.identity.registration.VerificationMailTransport.DeliveryResult.DELIVERY_REJECTED;
import static com.docukore.identity.registration.VerificationMailTransport.DeliveryResult.DELIVERY_UNAVAILABLE;
import static com.docukore.identity.registration.VerificationMailTransport.DeliveryResult.NOT_CONFIGURED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class GmailApiVerificationMailTransportTest
{
    /** Expected OAuth endpoint for token refresh requests. */
    private static final URI TOKEN_ENDPOINT = URI.create("https://oauth2.googleapis.com/token");
    /** Expected Gmail endpoint for message delivery requests. */
    private static final URI SEND_ENDPOINT = URI.create("https://gmail.googleapis.com/gmail/v1/users/me/messages/send");
    /** Fictional OAuth client identifier used to configure the transport. */
    private static final String CLIENT_ID = "1234567890-fictional.apps.googleusercontent.com";
    /** Fictional client secret containing characters that require form encoding. */
    private static final String CLIENT_SECRET = "fictional&secret+value";
    /** Fictional refresh token containing characters that require form encoding. */
    private static final String REFRESH_TOKEN = "fictional/refresh+token=value";
    /** Fictional authenticated sender mailbox used in MIME assertions. */
    private static final String SENDER = "sender@example.test";
    /** Fictional initial access token returned by scripted OAuth responses. */
    private static final String ACCESS_TOKEN = "fictional-access-token";
    /** Fictional replacement access token used in refresh and retry scenarios. */
    private static final String NEXT_TOKEN = "fictional-next-token";
    /** JSON mapper for inspecting outgoing Gmail message payloads. */
    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** Fictional verification mail shared by delivery and MIME assertions. */
    private static final VerificationMailTransport.VerificationMail MAIL =
        new VerificationMailTransport.VerificationMail(
            UUID.fromString("12345678-1234-1234-1234-123456789012"), "patient+tag@another.example",
            URI.create("https://app.example.test/verify?token=fictional-proof&label=caf%C3%A9"));

    @AfterEach
    void clearInterruptFlag()
    {
        Thread.interrupted();
    }

    @Test
    void constructionDoesNotContactGoogleAndMissingConfigurationDoesNotContactGoogle()
    {
        var http = new MockHttp();
        new GmailApiVerificationMailTransport(properties(), http.client, new MutableClock());
        var absent = new GmailApiVerificationMailTransport(
                new GmailProperties(null, null, null, null, null), http.client, new MutableClock());

        assertThat(absent.send(MAIL)).isEqualTo(NOT_CONFIGURED);
        assertThat(new GmailApiVerificationMailTransport(new GmailProperties(null, null, null, null, null))
                .send(MAIL)).isEqualTo(NOT_CONFIGURED);
        verifyNoInteractions(http.client);
    }

    @Test
    void refreshesWithFormEncodedCredentialsAndSendsBase64UrlMultipartMessageToArbitraryRecipient()
    {
        var http = new MockHttp().token(ACCESS_TOKEN, 3600).reply(200, "provider-message-body-must-be-discarded");
        var transport = transport(http, new MutableClock());

        assertThat(transport.send(MAIL)).isEqualTo(DELIVERED);
        assertThat(http.requests).hasSize(2);
        HttpRequest tokenRequest = http.requests.getFirst();
        assertThat(tokenRequest.uri()).isEqualTo(TOKEN_ENDPOINT);
        assertThat(tokenRequest.method()).isEqualTo("POST");
        assertThat(tokenRequest.headers().firstValue("Content-Type")).hasValue("application/x-www-form-urlencoded");
        assertThat(tokenRequest.headers().firstValue("Authorization")).isEmpty();
        assertThat(form(body(tokenRequest))).containsExactlyInAnyOrderEntriesOf(Map.of(
                "client_id", CLIENT_ID, "client_secret", CLIENT_SECRET,
                "refresh_token", REFRESH_TOKEN, "grant_type", "refresh_token"));
        HttpRequest sendRequest = http.requests.get(1);
        assertThat(sendRequest.uri()).isEqualTo(SEND_ENDPOINT);
        assertThat(sendRequest.method()).isEqualTo("POST");
        assertThat(sendRequest.timeout()).hasValue(Duration.ofSeconds(10));
        assertThat(sendRequest.headers().firstValue("Authorization")).hasValue("Bearer " + ACCESS_TOKEN);
        assertThat(sendRequest.headers().firstValue("Content-Type")).hasValue("application/json");
        var payload = JSON.readTree(body(sendRequest));
        assertThat(payload.size()).isEqualTo(1);
        String raw = payload.get("raw").asString();
        assertThat(raw).matches("[A-Za-z0-9_-]+").doesNotContain("=");
        String mime = new String(Base64.getUrlDecoder().decode(raw), StandardCharsets.UTF_8);
        assertThat(mime).startsWith("From: " + SENDER + "\r\nTo: " + MAIL.recipient() + "\r\n")
                .contains("MIME-Version: 1.0\r\n", "Content-Type: multipart/alternative; boundary=\"");
        assertThat(mime.replace("\r\n", "")).doesNotContain("\n", "\r");
        String encodedSubject = mime.lines().filter(line -> line.startsWith("Subject: ")).findFirst().orElseThrow();
        assertThat(decodeSubject(encodedSubject)).isEqualTo("Confirm your Docukore email");
        String boundary = mime.split("boundary=\"")[1].split("\"")[0];
        assertThat(mime).endsWith("--" + boundary + "--\r\n");
        String[] parts = mime.split("--" + boundary + "\r\n");
        assertThat(parts).hasSize(3);
        assertThat(parts[1]).contains("Content-Type: text/plain; charset=UTF-8\r\n",
                "Content-Transfer-Encoding: base64\r\n");
        assertThat(parts[2]).contains("Content-Type: text/html; charset=UTF-8\r\n");
        String text = decodePart(parts[1]);
        String html = decodePart(parts[2]);
        assertThat(text).isEqualTo("Confirm your Docukore email address by opening this link:\n"
                + MAIL.verificationLink().toASCIIString()
                + "\nIf you did not request registration, ignore this message.");
        assertThat(html).contains("Confirm your Docukore email address by opening this link:",
                "If you did not request registration, ignore this message.",
                "href=\"https://app.example.test/verify?token=fictional-proof&amp;label=caf%C3%A9\"");
        verify(http.responses.get(1), never()).body();
    }

    @Test
    void unicodeVerificationLinkSurvivesUtf8MimeAndHtmlEscaping()
    {
        var http = new MockHttp().token(ACCESS_TOKEN, 3600).reply(202, "ignored");
        var mail = new VerificationMailTransport.VerificationMail(MAIL.attemptId(), MAIL.recipient(),
                URI.create("https://app.example.test/verify/café?token=fictional-proof&note=%22bonjour%22"));

        assertThat(transport(http, new MutableClock()).send(mail)).isEqualTo(DELIVERED);
        String mime = decodedMime(http.requests.get(1));
        assertThat(mime).contains("charset=UTF-8");
        String boundary = mime.split("boundary=\"")[1].split("\"")[0];
        String[] parts = mime.split("--" + boundary + "\r\n");
        assertThat(decodePart(parts[1])).contains(mail.verificationLink().toASCIIString());
        assertThat(decodePart(parts[2])).contains("caf%C3%A9", "&amp;note=%22bonjour%22");
    }

    @ParameterizedTest
    @ValueSource(strings = {"patient@example.test\r\nBcc: attacker@example.test", "bad\nheader@example.test",
                            "one@example.test,two@example.test", "not-email", "a@-example.test", ".a@example.test", ""})
    void invalidRecipientNeverReachesHttp(String recipient)
    {
        var http = new MockHttp();
        var mail = new VerificationMailTransport.VerificationMail(MAIL.attemptId(), recipient, MAIL.verificationLink());

        assertThat(transport(http, new MutableClock()).send(mail)).isEqualTo(DELIVERY_REJECTED);
        verifyNoInteractions(http.client);
    }

    @Test
    void cachesTokenUntilExpiryMarginThenRefreshes()
    {
        var clock = new MutableClock();
        var http = new MockHttp().token(ACCESS_TOKEN, 120).reply(200, "ignored")
                .reply(200, "ignored").token(NEXT_TOKEN, 3600).reply(200, "ignored");
        var transport = transport(http, clock);

        assertThat(transport.send(MAIL)).isEqualTo(DELIVERED);
        clock.advance(Duration.ofSeconds(89));
        assertThat(transport.send(MAIL)).isEqualTo(DELIVERED);
        clock.advance(Duration.ofSeconds(1));
        assertThat(transport.send(MAIL)).isEqualTo(DELIVERED);
        assertThat(http.requests.stream().filter(request -> request.uri().equals(TOKEN_ENDPOINT))).hasSize(2);
        assertThat(http.requests.get(1).headers().firstValue("Authorization")).hasValue("Bearer " + ACCESS_TOKEN);
        assertThat(http.requests.get(2).headers().firstValue("Authorization")).hasValue("Bearer " + ACCESS_TOKEN);
        assertThat(http.requests.get(4).headers().firstValue("Authorization")).hasValue("Bearer " + NEXT_TOKEN);
    }

    @Test
    void tokenExpiresEvenWhenClockMovesBeyondItsActualLifetime()
    {
        var clock = new MutableClock();
        var http = new MockHttp().token(ACCESS_TOKEN, 60).reply(200, "ignored")
                .token(NEXT_TOKEN, 60).reply(200, "ignored");
        var transport = transport(http, clock);

        assertThat(transport.send(MAIL)).isEqualTo(DELIVERED);
        clock.advance(Duration.ofSeconds(61));
        assertThat(transport.send(MAIL)).isEqualTo(DELIVERED);
        assertThat(http.requests.get(3).headers().firstValue("Authorization")).hasValue("Bearer " + NEXT_TOKEN);
    }

    @Test
    void retriesAnUnauthorizedSendExactlyOnceUsingRefreshedTokenAndIdenticalMime()
    {
        var http = new MockHttp().token(ACCESS_TOKEN, 3600).reply(401, "sensitive-provider-details")
                .token(NEXT_TOKEN, 3600).reply(200, "ignored");

        assertThat(transport(http, new MutableClock()).send(MAIL)).isEqualTo(DELIVERED);
        assertThat(http.requests).hasSize(4);
        assertThat(body(http.requests.get(1))).isEqualTo(body(http.requests.get(3)));
        assertThat(http.requests.get(3).headers().firstValue("Authorization")).hasValue("Bearer " + NEXT_TOKEN);
        verify(http.responses.get(1), never()).body();
        verify(http.responses.get(3), never()).body();
    }

    @Test
    void secondUnauthorizedSendRejectsAndInvalidatesTheReplacementToken()
    {
        var http = new MockHttp().token(ACCESS_TOKEN, 3600).reply(401, "ignored")
                .token(NEXT_TOKEN, 3600).reply(401, "ignored")
                .reply(400, "revoked-refresh-token-details");
        var transport = transport(http, new MutableClock());

        assertThat(transport.send(MAIL)).isEqualTo(DELIVERY_REJECTED);
        assertThat(http.requests).hasSize(4);
        assertThat(transport.send(MAIL)).isEqualTo(DELIVERY_REJECTED);
        assertThat(http.requests.get(4).uri()).isEqualTo(TOKEN_ENDPOINT);
    }

    @Test
    void revokedRefreshAfterUnauthorizedSendDoesNotResend()
    {
        var http = new MockHttp().token(ACCESS_TOKEN, 3600).reply(401, "ignored")
                .reply(400, "{\"error\":\"invalid_grant\",\"error_description\":\"secret provider detail\"}");

        assertThat(transport(http, new MutableClock()).send(MAIL)).isEqualTo(DELIVERY_REJECTED);
        assertThat(http.requests).hasSize(3);
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 404})
    void tokenEndpointInvalidCredentialsAreRejectedWithoutSending(int status)
    {
        var http = new MockHttp().reply(status, "private-provider-error");

        assertThat(transport(http, new MutableClock()).send(MAIL)).isEqualTo(DELIVERY_REJECTED);
        assertThat(http.requests).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(ints = {429, 500, 503})
    void tokenEndpointQuotaAndServerFailuresAreUnavailable(int status)
    {
        var http = new MockHttp().reply(status, "private-provider-error");

        assertThat(transport(http, new MutableClock()).send(MAIL)).isEqualTo(DELIVERY_UNAVAILABLE);
        assertThat(http.requests).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(ints = {403, 429, 500, 503})
    void gmailQuotaAndServerFailuresAreUnavailableWithoutSendRetry(int status)
    {
        var http = new MockHttp().token(ACCESS_TOKEN, 3600).reply(status, "private-provider-error");

        assertThat(transport(http, new MutableClock()).send(MAIL)).isEqualTo(DELIVERY_UNAVAILABLE);
        assertThat(http.requests).hasSize(2);
        verify(http.responses.get(1), never()).body();
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 404, 422})
    void gmailNonQuotaClientErrorsAreRejected(int status)
    {
        var http = new MockHttp().token(ACCESS_TOKEN, 3600).reply(status, "private-provider-error");

        assertThat(transport(http, new MutableClock()).send(MAIL)).isEqualTo(DELIVERY_REJECTED);
        assertThat(http.requests).hasSize(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "not-json", "null", "[]", "{}",
        "{\"access_token\":123,\"expires_in\":3600,\"token_type\":\"Bearer\"}",
        "{\"access_token\":\"unsafe\\r\\nheader\",\"expires_in\":3600,\"token_type\":\"Bearer\"}",
        "{\"access_token\":\"a token\",\"expires_in\":3600,\"token_type\":\"Bearer\"}",
        "{\"access_token\":\"token\",\"expires_in\":0,\"token_type\":\"Bearer\"}",
        "{\"access_token\":\"token\",\"expires_in\":-1,\"token_type\":\"Bearer\"}",
        "{\"access_token\":\"token\",\"expires_in\":\"3600\",\"token_type\":\"Bearer\"}",
        "{\"access_token\":\"token\",\"expires_in\":1.5,\"token_type\":\"Bearer\"}",
        "{\"access_token\":\"token\",\"expires_in\":999999999999999999999,\"token_type\":\"Bearer\"}",
        "{\"access_token\":\"token\",\"expires_in\":3600,\"token_type\":\"Basic\"}",
        "{\"access_token\":\"token\",\"expires_in\":3600}"
    })
    void malformedOrUnsafeTokenResponsesAreUnavailableAndNeverSent(String response)
    {
        var http = new MockHttp().reply(200, response);

        assertThat(transport(http, new MutableClock()).send(MAIL)).isEqualTo(DELIVERY_UNAVAILABLE);
        assertThat(http.requests).hasSize(1);
    }

    @Test
    void oversizedTokenResponseAndAccessTokenAreNotAccepted()
    {
        var oversized = new MockHttp().reply(200, " ".repeat(16385) + token(ACCESS_TOKEN, 3600));
        var tokenTooLong = new MockHttp().token("x".repeat(4097), 3600);

        assertThat(transport(oversized, new MutableClock()).send(MAIL)).isEqualTo(DELIVERY_UNAVAILABLE);
        assertThat(transport(tokenTooLong, new MutableClock()).send(MAIL)).isEqualTo(DELIVERY_UNAVAILABLE);
        assertThat(oversized.requests).hasSize(1);
        assertThat(tokenTooLong.requests).hasSize(1);
    }

    @Test
    void ambiguousSendNetworkFailureIsUnavailableWithoutRetry()
    {
        var http = new MockHttp().token(ACCESS_TOKEN, 3600)
                .failure(new IOException("private-secret-provider-detail"));

        assertThat(transport(http, new MutableClock()).send(MAIL)).isEqualTo(DELIVERY_UNAVAILABLE);
        assertThat(http.requests).hasSize(2);
    }

    @Test
    void refreshNetworkFailureAndRuntimeFailureAreUnavailable()
    {
        var network = new MockHttp().failure(new IOException("private-provider-detail"));
        var runtime = new MockHttp().failure(new IllegalStateException("private-provider-detail"));

        assertThat(transport(network, new MutableClock()).send(MAIL)).isEqualTo(DELIVERY_UNAVAILABLE);
        assertThat(transport(runtime, new MutableClock()).send(MAIL)).isEqualTo(DELIVERY_UNAVAILABLE);
        assertThat(network.requests).hasSize(1);
        assertThat(runtime.requests).hasSize(1);
    }

    @Test
    void interruptedRefreshRestoresInterruptFlag()
    {
        var http = new MockHttp().failure(new InterruptedException("private-provider-detail"));

        assertThat(transport(http, new MutableClock()).send(MAIL)).isEqualTo(DELIVERY_INTERRUPTED);
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
        assertThat(http.requests).hasSize(1);
    }

    @Test
    void interruptedSendRestoresInterruptFlagAndIsNotRetried()
    {
        var http = new MockHttp().token(ACCESS_TOKEN, 3600)
                .failure(new InterruptedException("private-provider-detail"));

        assertThat(transport(http, new MutableClock()).send(MAIL)).isEqualTo(DELIVERY_INTERRUPTED);
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
        assertThat(http.requests).hasSize(2);
    }

    @Test
    void toStringNeverContainsCredentialsAccessTokenRecipientOrVerificationProof()
    {
        var http = new MockHttp().token(ACCESS_TOKEN, 3600).reply(200, "ignored");
        var transport = transport(http, new MutableClock());
        assertThat(transport.send(MAIL)).isEqualTo(DELIVERED);

        assertThat(transport.toString()).isEqualTo("GmailApiVerificationMailTransport[redacted]")
                .doesNotContain(CLIENT_ID, CLIENT_SECRET, REFRESH_TOKEN, ACCESS_TOKEN,
                        MAIL.recipient(), MAIL.verificationLink().toASCIIString());
    }

    private static GmailProperties properties()
    {
        return new GmailProperties(CLIENT_ID, CLIENT_SECRET, REFRESH_TOKEN, SENDER, null);
    }

    private static GmailApiVerificationMailTransport transport(MockHttp http, MutableClock clock)
    {
        return new GmailApiVerificationMailTransport(properties(), http.client, clock);
    }

    private static String token(String value, long lifetime)
    {
        return "{\"access_token\":\"" + value + "\",\"expires_in\":" + lifetime + ",\"token_type\":\"Bearer\"}";
    }

    private static Map<String, String> form(String body)
    {
        Map<String, String> values = new LinkedHashMap<>();
        for (String pair : body.split("&"))
        {
            String[] parts = pair.split("=", 2);
            values.put(URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
                    URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
        }
        return values;
    }

    private static String body(HttpRequest request)
    {
        var bytes = new java.io.ByteArrayOutputStream();
        var completion = new CompletableFuture<Void>();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<>()
        {
            @Override
            public void onSubscribe(Flow.Subscription subscription)
            {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(ByteBuffer item)
            {
                byte[] chunk = new byte[item.remaining()];
                item.get(chunk);
                bytes.writeBytes(chunk);
            }

            @Override
            public void onError(Throwable error)
            {
                completion.completeExceptionally(error);
            }

            @Override
            public void onComplete()
            {
                completion.complete(null);
            }
        });
        completion.join();
        return bytes.toString(StandardCharsets.UTF_8);
    }

    private static String decodedMime(HttpRequest request)
    {
        String raw = JSON.readTree(body(request)).get("raw").asString();
        return new String(Base64.getUrlDecoder().decode(raw), StandardCharsets.UTF_8);
    }

    private static String decodeSubject(String subject)
    {
        String value = subject.substring("Subject: ".length());
        if (value.startsWith("=?UTF-8?B?") && value.endsWith("?="))
        {
            return new String(Base64.getDecoder().decode(value.substring(10, value.length() - 2)),
                    StandardCharsets.UTF_8);
        }
        return value;
    }

    private static String decodePart(String part)
    {
        String content = part.split("\r\n\r\n", 2)[1].split("\r\n--", 2)[0];
        return new String(Base64.getMimeDecoder().decode(content), StandardCharsets.UTF_8);
    }

    private static final class MutableClock extends Clock
    {
        /** Controllable current instant for token expiry scenarios. */
        private Instant now = Instant.parse("2026-11-20T12:00:00Z");

        void advance(Duration duration)
        {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone()
        {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone)
        {
            return this;
        }

        @Override
        public Instant instant()
        {
            return now;
        }
    }

    private static final class MockHttp
    {
        /** Mock HTTP client supplied to the transport under test. */
        private final HttpClient client = mock(HttpClient.class);
        /** Scripted replies and failures consumed in request order. */
        private final Deque<Reply> replies = new ArrayDeque<>();
        /** Captured outgoing requests for endpoint, header and payload assertions. */
        private final List<HttpRequest> requests = new ArrayList<>();
        /** Mock responses retained to verify provider bodies remain unread. */
        private final List<HttpResponse<?>> responses = new ArrayList<>();

        MockHttp()
        {
            try
            {
                doAnswer(invocation ->
                {
                    HttpRequest request = invocation.getArgument(0);
                    requests.add(request);
                    if (replies.isEmpty())
                    {
                        throw new AssertionError("Unexpected HTTP request");
                    }
                    Reply reply = replies.removeFirst();
                    if (reply.failure() != null)
                    {
                        throw reply.failure();
                    }
                    HttpResponse.BodyHandler<?> handler = invocation.getArgument(1);
                    var info = mock(HttpResponse.ResponseInfo.class);
                    when(info.statusCode()).thenReturn(reply.status());
                    HttpResponse.BodySubscriber<?> subscriber = handler.apply(info);
                    subscriber.onSubscribe(mock(Flow.Subscription.class));
                    subscriber.onNext(List.of(ByteBuffer.wrap(reply.body().getBytes(StandardCharsets.UTF_8))));
                    subscriber.onComplete();
                    Object responseBody = subscriber.getBody().toCompletableFuture().join();
                    var response = mock(HttpResponse.class);
                    when(response.statusCode()).thenReturn(reply.status());
                    when(response.body()).thenReturn(responseBody);
                    responses.add(response);
                    return response;
                }).when(client).send(any(HttpRequest.class), any());
            } catch (IOException | InterruptedException error)
            {
                throw new AssertionError(error);
            }
        }

        MockHttp token(String value, long lifetime)
        {
            return reply(200, GmailApiVerificationMailTransportTest.token(value, lifetime));
        }

        MockHttp reply(int status, String body)
        {
            replies.addLast(new Reply(status, body, null));
            return this;
        }

        MockHttp failure(Exception error)
        {
            replies.addLast(new Reply(0, "", error));
            return this;
        }
    }

    private record Reply(int status, String body, Exception failure)
    {
        // Scripted replies contain fictional data only.
    }
}
