package com.docukore.platform.provisioning;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Capability-local error carrying only an allowlisted, non-sensitive code. */
public final class CabinetProvisioningException extends RuntimeException
{

    /**
     * Logger for this class.
     */
    private static final Logger LOG = LoggerFactory.getLogger(CabinetProvisioningException.class);

    /** Sanitized failure classification. */
    private final String code;

    /**
     * Creates an error without attaching a JDBC/Flyway cause that could disclose secrets.
     *
     * @param code the error code
     */
    public CabinetProvisioningException(String code)
    {
        super(safeCode(code));
        this.code = safeCode(code);
        LOG.debug("Provisioning exception raised - code: {}", this.code);
    }

    /**
     * Returns the safe machine-readable code.
     *
     * @return the error code
     */
    public String code()
    {
        return code;
    }

    /**
     * Restricts externally supplied codes to a bounded non-sensitive alphabet.
     *
     * @param value the raw code value
     * @return a sanitized code matching the allowlist pattern
     */
    private static String safeCode(String value)
    {
        if (value == null || !value.matches("^[A-Z][A-Z0-9_]{0,63}$"))
        {
            return "PROVISIONING_FAILED";
        }
        return value;
    }
}
