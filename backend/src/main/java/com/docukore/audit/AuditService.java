package com.docukore.audit;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.UUID;

import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.docukore.cabinet.access.CabinetMembershipRepository;
import com.docukore.identity.CurrentAccount;
import com.docukore.tenant.TenantContext;

/**
 * Tenant audit recording: mandatory writes join the business transaction; read-only VIEW events
 * are inserted synchronously after successful transaction completion, in a separate write transaction.
 * Call only after the protected business action succeeds, with tenant scope surrounding the transaction.
 */
@Service
public class AuditService
{
    /** Operations supported by the existing database constraint. */
    private static final Set<String> OPERATIONS = Set.of("CREATE", "UPDATE", "DELETE", "VIEW", "EXPORT",
            "PRINT", "SHARE");
    /** Fixed resource categories, never free-text clinical content. */
    private static final Set<String> ENTITY_TYPES = Set.of("patient", "consultation", "document", "prescription",
            "appointment", "membership", "cabinet", "medical_record", "payment");

    /** Insert-only tenant persistence. */
    private final AuditEventRepository auditRepository;
    /** Trusted authenticated account. */
    private final CurrentAccount currentAccount;
    /** Active cabinet membership and role. */
    private final CabinetMembershipRepository memberships;
    /** REQUIRED transaction boundary for atomic writes. */
    private final TransactionTemplate writes;
    /** Separate write boundary used only after a successful read-only transaction. */
    private final TransactionTemplate views;

    public AuditService(AuditEventRepository auditRepository, CurrentAccount currentAccount,
            CabinetMembershipRepository memberships,
            @Qualifier("transactionManager") PlatformTransactionManager transactionManager)
    {
        this.auditRepository = auditRepository;
        this.currentAccount = currentAccount;
        this.memberships = memberships;
        this.writes = new TransactionTemplate(transactionManager);
        this.views = new TransactionTemplate(transactionManager);
        this.views.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Validate and record an event, rejecting untrusted identity claims and unsafe payloads.
     * A participating REQUIRED boundary marks a business write rollback-only on any audit failure.
     *
     * @param event the event constructed by server-side application code, never a request DTO
     */
    public void recordEvent(AuditEvent event)
    {
        writes.executeWithoutResult(status ->
        {
            AuditEvent snapshot = trustedSnapshot(event);
            if (TransactionSynchronizationManager.isCurrentTransactionReadOnly())
            {
                if (!"VIEW".equals(snapshot.operation()))
                {
                    throw new IllegalStateException("Write auditing requires a writable business transaction");
                }
                String schema = TenantContext.getSchemaName();
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization()
                {
                    @Override
                    public void afterCommit()
                    {
                        requireTenant(snapshot.cabinetId(), schema);
                        views.executeWithoutResult(viewStatus -> auditRepository.save(snapshot));
                    }
                });
            } else
            {
                auditRepository.save(snapshot);
            }
        });
    }

    /**
     * Record using the trusted actor and active membership role; the legacy role argument is ignored.
     *
     * @param cabinetId the cabinet UUID, checked against the active tenant
     * @param entityType the allowlisted resource category
     * @param entityId the resource UUID
     * @param operation the supported action
     * @param userRole legacy caller role, not authoritative
     */
    public void recordEvent(UUID cabinetId, String entityType, UUID entityId, String operation, String userRole)
    {
        writes.executeWithoutResult(status ->
        {
            AuditEvent event = new AuditEventBuilder().cabinetId(cabinetId).entityType(entityType)
                .entityId(entityId).operation(operation).userId(currentAccount.requireId())
                .timestamp(OffsetDateTime.now(ZoneOffset.UTC)).build();
            recordEvent(event);
        });
    }

    private AuditEvent trustedSnapshot(AuditEvent event)
    {
        if (event == null || event.id() == null || event.entityId() == null || event.timestamp() == null
                || event.entityType() == null || event.operation() == null
                || !ENTITY_TYPES.contains(event.entityType()) || !OPERATIONS.contains(event.operation()))
        {
            throw new IllegalArgumentException("Invalid audit event");
        }
        if (!TenantContext.requireCabinetId().equals(event.cabinetId()))
        {
            throw new IllegalArgumentException("Event cabinet does not match active tenant");
        }
        UUID actor = currentAccount.requireId();
        if (!actor.equals(event.userId()))
        {
            throw new IllegalArgumentException("Event actor does not match authenticated account");
        }
        String role = memberships.findActiveRole(event.cabinetId(), actor)
            .orElseThrow(() -> new IllegalStateException("Active cabinet membership is required for auditing"));
        AuditPayloadPolicy.validate(event);
        AuditEvent snapshot = new AuditEvent(event.id(), TenantContext.requireCabinetId(), event.entityType(),
                event.entityId(), event.operation(), actor, role,
                event.timestamp().withOffsetSameInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS));
        snapshot.setIpAddress(event.ipAddress());
        snapshot.setChangedFields(event.changedFields());
        if (event.metadata() != null)
        {
            String requestId = MDC.get("requestId");
            String metadata = "{\"outcome\":\"SUCCESS\"}";
            if (requestId != null && requestId.matches(
                    "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
            {
                metadata = "{\"outcome\":\"SUCCESS\",\"requestId\":\"" + requestId + "\"}";
            }
            snapshot.setMetadata(metadata);
        }
        // User-agent headers and clinical values are deliberately not copied into the persisted event.
        return snapshot;
    }

    private static void requireTenant(UUID cabinetId, String schema)
    {
        if (!TenantContext.isPresent() || !cabinetId.equals(TenantContext.requireCabinetId())
                || !schema.equals(TenantContext.getSchemaName()))
        {
            throw new IllegalStateException("Tenant context changed before VIEW audit persistence");
        }
    }
}
