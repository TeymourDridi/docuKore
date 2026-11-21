package com.docukore.identity.registration;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/** Gmail send-only OAuth adapter. Request contents and provider responses are never logged. */
public final class GmailApiVerificationMailTransport implements VerificationMailTransport
{
    /** OAuth endpoint used to exchange the refresh token for an access token. */
    private static final URI TOKEN_ENDPOINT = URI.create("https://oauth2.googleapis.com/token");
    /** Gmail endpoint used to send verification messages for the authenticated account. */
    private static final URI SEND_ENDPOINT = URI.create("https://gmail.googleapis.com/gmail/v1/users/me/messages/send");
    /** Maximum number of bytes collected from a successful OAuth token response. */
    private static final int MAX_TOKEN_RESPONSE_BYTES = 16 * 1024;
    /** Safety margin subtracted from the token lifetime before refreshing. */
    private static final long EXPIRY_MARGIN_SECONDS = 30;
    /** Maximum accepted access token lifetime in seconds. */
    private static final long MAX_TOKEN_LIFETIME_SECONDS = 24 * 60 * 60;
    /** JSON mapper for token responses and Gmail message payloads. */
    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** Gmail OAuth, sender and request timeout configuration. */
    private final GmailProperties properties;
    /** HTTP client for OAuth token exchange and Gmail message delivery. */
    private final HttpClient client;
    /** Clock used to calculate and check the cached token deadline. */
    private final Clock clock;
    /** Cached bearer token and deadline are guarded by this instance's monitor. */
    private String accessToken;
    /** Cached token refresh deadline guarded by this instance's monitor. */
    private Instant refreshAt;

    public GmailApiVerificationMailTransport(GmailProperties properties)
    {
        this(properties, configuredClient(properties), Clock.systemUTC());
    }

    public GmailApiVerificationMailTransport(GmailProperties properties, HttpClient client, Clock clock)
    {
        this.properties = Objects.requireNonNull(properties, "Email properties are required");
        this.client = properties.configured() ? Objects.requireNonNull(client, "HTTP client is required") : null;
        this.clock = Objects.requireNonNull(clock, "Clock is required");
    }

    private static HttpClient configuredClient(GmailProperties properties)
    {
        Objects.requireNonNull(properties, "Email properties are required");
        return properties.configured() ? HttpClient.newBuilder().connectTimeout(properties.timeout())
                .followRedirects(HttpClient.Redirect.NEVER).build() : null;
    }

    @Override
    public DeliveryResult send(VerificationMail mail)
    {
        if (!properties.configured())
        {
            return DeliveryResult.NOT_CONFIGURED;
        }
        if (mail == null || !GmailProperties.mailbox(mail.recipient()) || mail.verificationLink() == null)
        {
            return DeliveryResult.DELIVERY_REJECTED;
        }
        try
        {
            String payload = payload(mail);
            String token = token();
            int status = send(payload, token);
            if (status == 401)
            {
                // A definitive authentication rejection is the only safe send retry.
                invalidate(token);
                token = token();
                status = send(payload, token);
                if (status == 401)
                {
                    invalidate(token);
                }
            }
            if (status >= 200 && status < 300)
            {
                return DeliveryResult.DELIVERED;
            }
            return status == 403 || status == 429 || status >= 500 ? DeliveryResult.DELIVERY_UNAVAILABLE
                    : DeliveryResult.DELIVERY_REJECTED;
        } catch (TokenRejectedException error)
        {
            return DeliveryResult.DELIVERY_REJECTED;
        } catch (InterruptedException error)
        {
            Thread.currentThread().interrupt();
            return DeliveryResult.DELIVERY_INTERRUPTED;
        } catch (IOException | RuntimeException error)
        {
            // A failed send may already have delivered; never automatically repeat it.
            return DeliveryResult.DELIVERY_UNAVAILABLE;
        }
    }

    private int send(String payload, String token) throws IOException, InterruptedException
    {
        HttpRequest request = HttpRequest.newBuilder(SEND_ENDPOINT).timeout(properties.timeout())
                .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8)).build();
        // Gmail message IDs and all provider error bodies are discarded, not parsed or retained.
        return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private synchronized String token() throws IOException, InterruptedException, TokenRejectedException
    {
        Instant now = clock.instant();
        if (accessToken != null && now.isBefore(refreshAt))
        {
            return accessToken;
        }
        accessToken = null;
        refreshAt = null;
        String form = "client_id=" + formValue(properties.clientId())
                + "&client_secret=" + formValue(properties.clientSecret())
                + "&refresh_token=" + formValue(properties.refreshToken()) + "&grant_type=refresh_token";
        HttpRequest request = HttpRequest.newBuilder(TOKEN_ENDPOINT).timeout(properties.timeout())
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8)).build();
        HttpResponse<byte[]> response = client.send(request, info ->
                info.statusCode() >= 200 && info.statusCode() < 300 ? new TokenBodySubscriber()
                        : HttpResponse.BodySubscribers.replacing(new byte[0]));
        int status = response.statusCode();
        if (status >= 400 && status < 500 && status != 429)
        {
            throw new TokenRejectedException();
        }
        if (status < 200 || status >= 300)
        {
            throw new IOException("Email token service unavailable");
        }
        byte[] bytes = response.body();
        if (bytes == null || bytes.length > MAX_TOKEN_RESPONSE_BYTES)
        {
            throw new IOException("Invalid email token response");
        }
        JsonNode responseBody = JSON.readTree(bytes);
        if (responseBody == null || !responseBody.isObject())
        {
            throw new IOException("Invalid email token response");
        }
        JsonNode tokenValue = responseBody.get("access_token");
        JsonNode tokenType = responseBody.get("token_type");
        JsonNode lifetime = responseBody.get("expires_in");
        if (tokenValue == null || !tokenValue.isString() || !bearerToken(tokenValue.stringValue())
                || tokenType == null || !tokenType.isString() || !"Bearer".equalsIgnoreCase(tokenType.stringValue())
                || lifetime == null || !lifetime.isIntegralNumber() || !lifetime.canConvertToLong())
        {
            throw new IOException("Invalid email token response");
        }
        long expiresIn = lifetime.longValue();
        if (expiresIn <= 0 || expiresIn > MAX_TOKEN_LIFETIME_SECONDS)
        {
            throw new IOException("Invalid email token response");
        }
        // Base the conservative cache deadline on request start, not response completion.
        refreshAt = now.plusSeconds(expiresIn - Math.min(EXPIRY_MARGIN_SECONDS, expiresIn));
        accessToken = tokenValue.stringValue();
        return accessToken;
    }

    private synchronized void invalidate(String rejectedToken)
    {
        if (Objects.equals(accessToken, rejectedToken))
        {
            accessToken = null;
            refreshAt = null;
        }
    }

    private static boolean bearerToken(String token)
    {
        return token != null && !token.isEmpty() && token.length() <= 4096
                && token.matches("[A-Za-z0-9._~+/-]+=*");
    }

    private static String formValue(String value)
    {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String payload(VerificationMail mail)
    {
        String link = mail.verificationLink().toASCIIString();
        String text = "Confirm your Docukore email address by opening this link:\n" + link
                + "\nIf you did not request registration, ignore this message.";
        String escapedLink = html(link);
        String markup = "<!doctype html><html><body><p>Confirm your Docukore email address by opening this link:</p>"
                + "<p><a href=\"" + escapedLink + "\">" + escapedLink + "</a></p>"
                + "<p>If you did not request registration, ignore this message.</p></body></html>";
        String boundary = "docukore_" + UUID.randomUUID();
        String mime = "From: " + properties.sender() + "\r\nTo: " + mail.recipient()
                + "\r\nSubject: Confirm your Docukore email\r\nMIME-Version: 1.0\r\n"
                + "Content-Type: multipart/alternative; boundary=\"" + boundary + "\"\r\n\r\n"
                + part(boundary, "text/plain", text) + part(boundary, "text/html", markup)
                + "--" + boundary + "--\r\n";
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(mime.getBytes(StandardCharsets.UTF_8));
        return JSON.writeValueAsString(JSON.createObjectNode().put("raw", raw));
    }

    private static String part(String boundary, String contentType, String content)
    {
        String encoded = Base64.getMimeEncoder(76, new byte[]{'\r', '\n'})
                .encodeToString(content.getBytes(StandardCharsets.UTF_8));
        return "--" + boundary + "\r\nContent-Type: " + contentType + "; charset=UTF-8\r\n"
                + "Content-Transfer-Encoding: base64\r\n\r\n" + encoded + "\r\n";
    }

    private static String html(String value)
    {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    @Override
    public String toString()
    {
        return "GmailApiVerificationMailTransport[redacted]";
    }

    /** A fixed application error: provider details and credentials never become exception data. */
    private static final class TokenRejectedException extends Exception
    {
        TokenRejectedException()
        {
            super("Email credentials rejected");
        }
    }

    /** Collect only the bounded OAuth success response; cancel immediately when the limit is exceeded. */
    private static final class TokenBodySubscriber implements HttpResponse.BodySubscriber<byte[]>
    {
        /** Future completed with the token response bytes or a safe failure. */
        private final CompletableFuture<byte[]> completion = new CompletableFuture<>();
        /** Buffer collecting the bounded OAuth token response body. */
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        /** Response subscription used to request data and cancel oversized bodies. */
        private Flow.Subscription subscription;

        @Override
        public CompletionStage<byte[]> getBody()
        {
            return completion;
        }

        @Override
        public void onSubscribe(Flow.Subscription incoming)
        {
            subscription = incoming;
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers)
        {
            if (completion.isDone())
            {
                return;
            }
            for (ByteBuffer buffer : buffers)
            {
                if (buffer.remaining() > MAX_TOKEN_RESPONSE_BYTES - bytes.size())
                {
                    subscription.cancel();
                    completion.completeExceptionally(new IOException("Invalid email token response"));
                    return;
                }
                byte[] chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                bytes.writeBytes(chunk);
            }
        }

        @Override
        public void onError(Throwable error)
        {
            completion.completeExceptionally(new IOException("Email token service unavailable"));
        }

        @Override
        public void onComplete()
        {
            completion.complete(bytes.toByteArray());
        }
    }
}
