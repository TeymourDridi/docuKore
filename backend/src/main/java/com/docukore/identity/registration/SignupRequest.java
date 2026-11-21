package com.docukore.identity.registration;

import java.util.UUID;

public record SignupRequest(String username, String email, String fullName, String cabinetName,
        String password, UUID idempotencyKey)
{
    @Override
    public String toString()
    {
        return "SignupRequest[redacted]";
    }
}
