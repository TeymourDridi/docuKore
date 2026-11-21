package com.docukore.identity.registration;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

public final class VerificationTokens
{
    /**
     * Cryptographically secure random number generator for token generation.
     */
    private final SecureRandom random;

    public VerificationTokens()
    {
        this.random = new SecureRandom();
    }

    public IssuedToken issue()
    {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String value = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        return new IssuedToken(value, hash(value));
    }

    public String hash(String value)
    {
        if (value == null || !value.matches("[A-Za-z0-9_-]{43}"))
        {
            throw new IllegalArgumentException("Invalid verification proof");
        }
        try
        {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException error)
        {
            throw new IllegalStateException("Verification hashing is unavailable");
        }
    }

    public record IssuedToken(String value, String hash)
    {
        @Override
        public String toString()
        {
            return "IssuedToken[redacted]";
        }
    }
}
