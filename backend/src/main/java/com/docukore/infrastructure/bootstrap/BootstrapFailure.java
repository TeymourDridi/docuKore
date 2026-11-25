package com.docukore.infrastructure.bootstrap;

/** A fixed, credential-free failure without the original exception or SQL attached. */
final class BootstrapFailure extends RuntimeException
{
    /** Serialization identifier. */
    private static final long serialVersionUID = 1L;

    /**
     * Construct a failure using only a source-controlled failure code.
     *
     * @param code fixed diagnostic code
     */
    BootstrapFailure(String code)
    {
        super(code, null, false, false);
    }
}
