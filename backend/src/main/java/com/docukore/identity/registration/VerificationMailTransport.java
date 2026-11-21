package com.docukore.identity.registration;

import java.net.URI;
import java.util.UUID;

/** External delivery boundary. Implementations return bounded codes, never provider errors. */
public interface VerificationMailTransport
{
    DeliveryResult send(VerificationMail mail);

    enum DeliveryResult
    {
        /** Mail delivered successfully. */
        DELIVERED,
        /** Mail transport not configured. */
        NOT_CONFIGURED,
        /** Mail rejected by provider. */
        DELIVERY_REJECTED,
        /** Mail transport temporarily unavailable. */
        DELIVERY_UNAVAILABLE,
        /** Mail delivery interrupted. */
        DELIVERY_INTERRUPTED
    }

    record VerificationMail(UUID attemptId, String recipient, URI verificationLink)
    {
        @Override
        public String toString()
        {
            return "VerificationMail[redacted]";
        }
    }
}
