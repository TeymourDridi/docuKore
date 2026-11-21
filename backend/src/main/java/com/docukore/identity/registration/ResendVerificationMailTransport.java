package com.docukore.identity.registration;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

/** Server-side Resend adapter. Provider bodies, headers and request payloads are never logged. */
public final class ResendVerificationMailTransport implements VerificationMailTransport
{
    /** Resend API endpoint. */
    private static final URI ENDPOINT = URI.create("https://api.resend.com/emails");
    /** Resend configuration properties. */
    private final ResendProperties properties;
    /** HTTP client for Resend API calls. */
    private final HttpClient client;

    public ResendVerificationMailTransport(ResendProperties properties)
    {
        this(properties, HttpClient.newBuilder().connectTimeout(properties.timeout())
                .followRedirects(HttpClient.Redirect.NEVER).build());
    }

    ResendVerificationMailTransport(ResendProperties properties, HttpClient client)
    {
        this.properties = properties;
        this.client = client;
    }

    @Override
    public DeliveryResult send(VerificationMail mail)
    {
        if (!properties.configured())
        {
            return DeliveryResult.NOT_CONFIGURED;
        }
        String text = "Confirm your Docukore email address by opening this link:\n"
                + mail.verificationLink().toASCIIString()
                + "\nIf you did not request registration, ignore this message.";
        String payload = "{\"from\":" + json(properties.sender()) + ",\"to\":["
                + json(mail.recipient()) + "],\"subject\":\"Confirm your Docukore email\",\"text\":"
                + json(text) + "}";
        try
        {
            HttpRequest request = HttpRequest.newBuilder(ENDPOINT).timeout(properties.timeout())
                    .header("Authorization", "Bearer " + properties.apiKey())
                    .header("Content-Type", "application/json")
                    .header("Idempotency-Key", "signup-verification/" + mail.attemptId())
                    .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8)).build();
            // No provider body is retained, even on errors.
            int status = client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            if (status >= 200 && status < 300)
            {
                return DeliveryResult.DELIVERED;
            }
            return status == 429 || status >= 500 ? DeliveryResult.DELIVERY_UNAVAILABLE
                    : DeliveryResult.DELIVERY_REJECTED;
        } catch (InterruptedException error)
        {
            Thread.currentThread().interrupt();
            return DeliveryResult.DELIVERY_INTERRUPTED;
        } catch (IOException | RuntimeException error)
        {
            return DeliveryResult.DELIVERY_UNAVAILABLE;
        }
    }

    private static String json(String value)
    {
        StringBuilder result = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++)
        {
            char character = value.charAt(i);
            switch (character)
            {
                case '\\' -> result.append("\\\\");
                case '"' -> result.append("\\\"");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");
                default ->
                {
                    if (character < 32)
                    {
                        result.append(String.format("\\u%04x", (int) character));
                    } else
                    {
                        result.append(character);
                    }
                } }
        }
        return result.append('"').toString();
    }
}
