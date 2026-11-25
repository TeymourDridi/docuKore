package com.docukore.audit;

import jakarta.persistence.EntityManagerFactory;
import org.springframework.orm.jpa.EntityManagerFactoryUtils;
import org.springframework.stereotype.Repository;

/** Append-only audit persistence using the existing tenant transaction-bound entity manager. */
@Repository
public class AuditEventRepository
{
    /** Factory used to obtain the existing tenant transaction-bound entity manager. */
    private final EntityManagerFactory entityManagers;

    public AuditEventRepository(EntityManagerFactory entityManagers)
    {
        this.entityManagers = entityManagers;
    }

    /**
     * Insert, never merge, an audit event and expose mandatory persistence failures immediately.
     *
     * @param event the validated event
     * @return the inserted event
     */
    public AuditEvent save(AuditEvent event)
    {
        var entityManager = EntityManagerFactoryUtils.getTransactionalEntityManager(entityManagers);
        if (entityManager == null || !entityManager.isJoinedToTransaction())
        {
            throw new IllegalStateException("Audit persistence requires a tenant transaction");
        }
        entityManager.persist(event);
        entityManager.flush();
        return event;
    }
}
