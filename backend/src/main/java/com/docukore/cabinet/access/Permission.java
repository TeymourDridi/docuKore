package com.docukore.cabinet.access;

/**
 * Permission levels for cabinet membership operations.
 *
 * NONE: No access.
 * READ: Read-only access to cabinet resources.
 * READ_WRITE: Full read and write access.
 *
 * Permission levels form a hierarchy: READ_WRITE permits READ and NONE,
 * READ permits NONE.
 */
public enum Permission
{
    /** No access to cabinet resources. */
    NONE,

    /** Read-only access to cabinet resources. */
    READ,

    /** Full read and write access to cabinet resources. */
    READ_WRITE;

    /**
     * Check if this permission level permits the required level.
     *
     * @param required The required permission level
     * @return true if this permission permits the required level
     */
    public boolean permits(Permission required)
    {
        if (this == READ_WRITE)
        {
            return true;
        }
        if (this == READ && required == READ)
        {
            return true;
        }
        if (required == NONE)
        {
            return true;
        }
        return false;
    }
}
