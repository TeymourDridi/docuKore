package com.docukore.tenant;

import org.hibernate.SessionEventListener;
import org.hibernate.boot.Metadata;
import org.hibernate.boot.spi.BootstrapContext;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.event.service.spi.EventListenerRegistry;
import org.hibernate.event.spi.AbstractSessionEvent;
import org.hibernate.event.spi.AutoFlushEvent;
import org.hibernate.event.spi.AutoFlushEventListener;
import org.hibernate.event.spi.DeleteContext;
import org.hibernate.event.spi.DeleteEvent;
import org.hibernate.event.spi.DeleteEventListener;
import org.hibernate.event.spi.EventType;
import org.hibernate.event.spi.FlushEvent;
import org.hibernate.event.spi.FlushEventListener;
import org.hibernate.event.spi.LoadEvent;
import org.hibernate.event.spi.LoadEventListener;
import org.hibernate.event.spi.MergeContext;
import org.hibernate.event.spi.MergeEvent;
import org.hibernate.event.spi.MergeEventListener;
import org.hibernate.event.spi.PersistContext;
import org.hibernate.event.spi.PersistEvent;
import org.hibernate.event.spi.PersistEventListener;
import org.hibernate.event.spi.RefreshContext;
import org.hibernate.event.spi.RefreshEvent;
import org.hibernate.event.spi.RefreshEventListener;
import org.hibernate.integrator.spi.Integrator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/** Guards both cached entity access and statements on an already acquired connection. */
public final class TenantPersistenceGuard implements Integrator, LoadEventListener, PersistEventListener,
    MergeEventListener, DeleteEventListener, RefreshEventListener, FlushEventListener, AutoFlushEventListener
{

    /**
     * Logger for this class.
     */
    private static final Logger LOG = LoggerFactory.getLogger(TenantPersistenceGuard.class);

    @Override
    public void integrate(Metadata metadata, BootstrapContext bootstrapContext, SessionFactoryImplementor factory)
    {
        LOG.debug("Registering TenantPersistenceGuard as Hibernate event listener");
        EventListenerRegistry events = factory.getServiceRegistry().getService(EventListenerRegistry.class);
        events.prependListeners(EventType.LOAD, this);
        events.prependListeners(EventType.PERSIST, this);
        events.prependListeners(EventType.PERSIST_ONFLUSH, this);
        events.prependListeners(EventType.MERGE, this);
        events.prependListeners(EventType.DELETE, this);
        events.prependListeners(EventType.REFRESH, this);
        events.prependListeners(EventType.FLUSH, this);
        events.prependListeners(EventType.AUTO_FLUSH, this);
    }

    private static void check(AbstractSessionEvent event)
    {
        Object tenant = event.getSession().getTenantIdentifierValue();
        if (!(tenant instanceof UUID cabinetId))
        {
            LOG.error("Persistence session has no valid tenant UUID - type: {}, value: {}",
                tenant != null ? tenant.getClass() : "null", tenant);
            throw new IllegalStateException("Persistence session has no valid tenant UUID");
        }
        TenantContext.requireMatches(cabinetId);
    }

    @Override
    public void onLoad(LoadEvent event, LoadEventListener.LoadType type)
    {
        check(event);
    }

    @Override
    public void onPersist(PersistEvent event)
    {
        check(event);
    }

    @Override
    public void onPersist(PersistEvent event, PersistContext context)
    {
        check(event);
    }

    @Override
    public void onMerge(MergeEvent event)
    {
        check(event);
    }

    @Override
    public void onMerge(MergeEvent event, MergeContext context)
    {
        check(event);
    }

    @Override
    public void onDelete(DeleteEvent event)
    {
        check(event);
    }

    @Override
    public void onDelete(DeleteEvent event, DeleteContext context)
    {
        check(event);
    }

    @Override
    public void onRefresh(RefreshEvent event)
    {
        check(event);
    }

    @Override
    public void onRefresh(RefreshEvent event, RefreshContext context)
    {
        check(event);
    }

    @Override
    public void onFlush(FlushEvent event)
    {
        check(event);
    }

    @Override
    public void onAutoFlush(AutoFlushEvent event)
    {
        check(event);
    }

    public static final class Statements implements SessionEventListener
    {

        /** Cabinet ID captured at statement listener creation. */
        private final UUID cabinetId;

        public Statements()
        {
            this.cabinetId = TenantContext.requireCabinetId();
            LOG.debug("Statement listener created - tracking cabinet: {}", cabinetId);
        }

        @Override
        public void jdbcPrepareStatementStart()
        {
            TenantContext.requireMatches(cabinetId);
        }

        @Override
        public void jdbcExecuteStatementStart()
        {
            TenantContext.requireMatches(cabinetId);
        }

        @Override
        public void jdbcExecuteBatchStart()
        {
            TenantContext.requireMatches(cabinetId);
        }
    }
}