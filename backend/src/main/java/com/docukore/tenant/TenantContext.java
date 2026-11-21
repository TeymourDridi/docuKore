package com.docukore.tenant;

import java.util.UUID;

/**
 * Trusted cabinet/schema binding for one operation on one thread. Nesting is rejected, even for the same tenant.
 * Explicit request cleanup detaches the current scope; it never restores an earlier binding.
 */
public final class TenantContext
{

    /** Thread-local storage for the current tenant binding. */
    private static final ThreadLocal<Binding> CURRENT = new ThreadLocal<>();

    private TenantContext()
    {
    }

    /** Tenant binding containing cabinet ID and schema name.
     *
     * @param cabinetId  the cabinet UUID
     * @param schemaName the tenant schema name
     */
    public record Binding(UUID cabinetId, String schemaName)
    {
    }

    public static UUID requireCabinetId()
    {
        Binding binding = CURRENT.get();
        if (binding == null)
        {
            throw new IllegalStateException("Trusted tenant context is required");
        }
        return binding.cabinetId();
    }

    public static String getSchemaName()
    {
        Binding binding = CURRENT.get();
        if (binding == null)
        {
            throw new IllegalStateException("Trusted tenant context is required");
        }
        return binding.schemaName();
    }

    public static boolean isPresent()
    {
        return CURRENT.get() != null;
    }

    /**
     * Opens the sole active scope; identifier syntax validation does not replace trusted schema resolution.
     *
     * @param cabinetId the trusted cabinet UUID
     * @param schemaName the resolved lowercase schema identifier, at most 63 ASCII characters
     * @return the owner-thread scope for this exact binding
     */
    static Scope open(UUID cabinetId, String schemaName)
    {
        if (cabinetId == null || cabinetId.equals(new UUID(0, 0)))
        {
            throw new IllegalArgumentException("A valid cabinet UUID is required");
        }
        if (schemaName == null || !schemaName.matches("[a-z_][a-z0-9_]{0,62}"))
        {
            throw new IllegalArgumentException("A valid schema name is required");
        }
        if (isPresent())
        {
            throw new IllegalStateException("Tenant context is already established");
        }
        Binding binding = new Binding(cabinetId, schemaName);
        Scope scope = new Scope(Thread.currentThread(), binding);
        CURRENT.set(binding);
        return scope;
    }

    /** Unconditional current-thread request cleanup; detached scopes cannot affect later bindings. */
    public static void clear()
    {
        CURRENT.remove();
    }

    static void requireMatches(UUID cabinetId)
    {
        if (!requireCabinetId().equals(cabinetId))
        {
            throw new IllegalStateException("Persistence tenant differs from trusted context");
        }
    }

    static final class Scope implements AutoCloseable
    {

        /** Owning thread for validation. */
        private final Thread owner;

        /** Exact binding created by this scope, not an equal-valued replacement. */
        private final Binding binding;

        /** Whether the scope has been closed. */
        private boolean closed;

        private Scope(Thread owner, Binding binding)
        {
            this.owner = owner;
            this.binding = binding;
        }

        /** Owner-thread close is idempotent and removes only this exact binding; detached close is harmless. */
        @Override
        public void close()
        {
            if (Thread.currentThread() != owner)
            {
                throw new IllegalStateException("Tenant scope must close on its owning thread");
            }
            if (!closed)
            {
                closed = true;
                if (CURRENT.get() == binding)
                {
                    CURRENT.remove();
                }
            }
        }
    }
}