package com.docukore.infrastructure.bootstrap;

/** Explicit operation boundaries; no implicit reset or production mode exists. */
enum BootstrapMode
{
    /** Nondestructive bootstrap of the application database. */
    DEVELOPMENT,
    /** Nondestructive bootstrap of the integration-test database. */
    TEST,
    /** Explicit recreation of the two existing, verified local disposable databases. */
    RESET_DEVELOPMENT;

    /**
     * Parse the only accepted command-line argument without echoing supplied input.
     *
     * @param arguments command-line arguments
     * @return selected operation
     */
    static BootstrapMode parse(String[] arguments)
    {
        if (arguments == null || arguments.length != 1)
        {
            throw new BootstrapFailure("BOOTSTRAP_MODE_INVALID");
        }
        return switch (arguments[0])
        {
            case "development" -> DEVELOPMENT;
            case "test" -> TEST;
            case "reset-development" -> RESET_DEVELOPMENT;
            default -> throw new BootstrapFailure("BOOTSTRAP_MODE_INVALID");
        };
    }
}
