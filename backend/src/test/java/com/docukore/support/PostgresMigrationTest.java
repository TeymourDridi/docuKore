package com.docukore.support;

import java.util.UUID;

/** Migration test base using shared PostgreSQL instance. */
public abstract class PostgresMigrationTest extends SharedPostgresTest
{

    /**
     * Creates a cabinet with proper sequential allocation for V10+ compatibility.
     *
     * @param owner the owner user UUID
     * @return the created cabinet UUID
     */
    protected UUID cabinetWithAllocation(UUID owner)
    {
        return createCabinetWithAllocation(owner);
    }
}