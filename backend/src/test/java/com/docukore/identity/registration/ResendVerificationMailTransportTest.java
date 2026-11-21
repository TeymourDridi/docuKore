package com.docukore.identity.registration;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ResendVerificationMailTransportTest
{
    @Test
    @SuppressWarnings("unchecked")
    void preservesResendAcceptanceAndBoundedFailureContract() throws Exception
    {
        HttpClient client = mock(HttpClient.class);
        HttpResponse<Void> accepted = mock(HttpResponse.class);
        HttpResponse<Void> limited = mock(HttpResponse.class);
        HttpResponse<Void> rejected = mock(HttpResponse.class);
        when(accepted.statusCode()).thenReturn(200);
        when(limited.statusCode()).thenReturn(429);
        when(rejected.statusCode()).thenReturn(401);
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(accepted, limited, rejected);
        var transport = new ResendVerificationMailTransport(
                new ResendProperties("fictional-key", "sender@example.test", null), client);
        var mail = new VerificationMailTransport.VerificationMail(UUID.randomUUID(), "recipient@example.test",
                URI.create("https://app.example.test/verify-email#token=fictional-proof"));
        assertThat(transport.send(mail)).isEqualTo(VerificationMailTransport.DeliveryResult.DELIVERED);
        assertThat(transport.send(mail)).isEqualTo(VerificationMailTransport.DeliveryResult.DELIVERY_UNAVAILABLE);
        assertThat(transport.send(mail)).isEqualTo(VerificationMailTransport.DeliveryResult.DELIVERY_REJECTED);
    }

    @Test
    void unconfiguredResendMakesNoRequest()
    {
        HttpClient client = mock(HttpClient.class);
        var transport = new ResendVerificationMailTransport(new ResendProperties(null, null, null), client);
        assertThat(transport.send(null)).isEqualTo(VerificationMailTransport.DeliveryResult.NOT_CONFIGURED);
        verifyNoInteractions(client);
    }
}
