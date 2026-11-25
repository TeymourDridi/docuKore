package com.docukore.cabinet.access;

public class AccessDeniedException extends RuntimeException
{

    /** Reason the cabinet operation was denied. */
    private final String reason;

    public AccessDeniedException(String reason)
    {
        super("Access denied: " + reason);
        this.reason = reason;
    }

    public String reason()
    {
        return reason;
    }
}
