package com.docukore.identity.registration;

import java.util.UUID;

public record SignupResponse(UUID registrationId, UUID cabinetId, Status status)
{
    public enum Status
    {
        /** Registration pending email verification. */
        PENDING_EMAIL_VERIFICATION,
        /** Email verification delivery failed. */
        DELIVERY_FAILED,
        /** Cabinet provisioning in progress. */
        PROVISIONING_PENDING,
        /** Registration fully active. */
        ACTIVE
    }
}
