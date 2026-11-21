package com.docukore.platform.cabinet;

import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.fail;

/** Calls the proposed production boundary without adding a stub implementation to src/main. */
final class CabinetRegistrationRepositoryContract
{

    /** Data source for the test database. */
    private final DataSource dataSource;

    /** Platform transaction manager. */
    private final PlatformTransactionManager transactionManager;

    /** Platform schema name. */
    private final String platformSchema;

    CabinetRegistrationRepositoryContract(DataSource dataSource,
                                          PlatformTransactionManager transactionManager,
                                          String platformSchema)
    {
        this.dataSource = dataSource;
        this.transactionManager = transactionManager;
        this.platformSchema = platformSchema;
    }

    void requireImplemented()
    {
        try
        {
            Class<?> type = Class.forName("com.docukore.platform.cabinet.CabinetRegistrationRepository");
            type.getConstructor(DataSource.class, PlatformTransactionManager.class, String.class);
            type.getMethod("allocate", UUID.class);
        } catch (ReflectiveOperationException failure)
        {
            fail("Stage B allocation repository contract is not implemented; no test-only substitute", failure);
        }
    }

    Allocation allocate(UUID originalRegistrationId)
    {
        try
        {
            Class<?> type = Class.forName("com.docukore.platform.cabinet.CabinetRegistrationRepository");
            Object repository = type.getConstructor(DataSource.class, PlatformTransactionManager.class, String.class)
                .newInstance(dataSource, transactionManager, platformSchema);
            Object result = type.getMethod("allocate", UUID.class).invoke(repository, originalRegistrationId);
            Class<?> resultType = result.getClass();
            return new Allocation(
                (UUID) resultType.getMethod("cabinetId").invoke(result),
                (UUID) resultType.getMethod("reservationId").invoke(result),
                ((Number) resultType.getMethod("schemaNumber").invoke(result)).longValue(),
                (String) resultType.getMethod("schemaName").invoke(result));
        } catch (InvocationTargetException failure)
        {
            Throwable cause = failure.getCause();
            if (cause instanceof RuntimeException runtime)
            {
                throw runtime;
            }
            if (cause instanceof Error error)
            {
                throw error;
            }
            throw new AssertionError("Unexpected checked failure at allocation boundary", cause);
        } catch (ReflectiveOperationException failure)
        {
            return fail("Stage B allocation repository contract is not implemented; no test-only substitute", failure);
        }
    }

    record Allocation(UUID cabinetId, UUID reservationId, long schemaNumber, String schemaName)
    {
    }
}