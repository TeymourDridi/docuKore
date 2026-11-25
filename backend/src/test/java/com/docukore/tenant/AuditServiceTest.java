package com.docukore.tenant;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import com.docukore.audit.AuditEvent;
import com.docukore.audit.AuditEventBuilder;
import com.docukore.audit.AuditEventRepository;
import com.docukore.audit.AuditService;
import com.docukore.cabinet.access.CabinetMembershipRepository;
import com.docukore.identity.CurrentAccount;

/**
 * Unit tests for AuditService.
 *
 * Placed in com.docukore.tenant to access package-private TenantContext.open().
 * Tests event recording, actor validation, tenant isolation, and cross-cabinet denial.
 * Does not require database infrastructure.
 */
class AuditServiceTest
{

    /** Audit service under test. */
    private AuditService service;

    /** Mocked append-only persistence boundary. */
    private AuditEventRepository auditRepository;

    /** Mocked authenticated account lookup. */
    private CurrentAccount currentAccount;

    /** Mocked trusted platform membership lookup. */
    private CabinetMembershipRepository memberships;

    /** Active cabinet used by ordinary test events. */
    private final UUID cabinetId = UUID.randomUUID();

    /** Authenticated account used by ordinary test events. */
    private final UUID userId = UUID.randomUUID();

    /** Fictional patient resource identifier. */
    private final UUID patientId = UUID.randomUUID();

    /** UTC timestamp supplied to event construction. */
    private final OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

    @BeforeEach
    void setUp()
    {
        auditRepository = mock(AuditEventRepository.class);
        currentAccount = mock(CurrentAccount.class);
        memberships = mock(CabinetMembershipRepository.class);
        when(memberships.findActiveRole(cabinetId, userId)).thenReturn(Optional.of("OWNER"));
        service = new AuditService(auditRepository, currentAccount, memberships, new UnitTransactionManager());
    }

    @AfterEach
    void clearTenantContext()
    {
        TenantContext.clear();
    }

    @Test
    @DisplayName("Event recorded successfully with correct actor and tenant")
    void eventRecordedSuccessfully()
    {
        when(currentAccount.requireId()).thenReturn(userId);
        AuditEvent event = new AuditEventBuilder().cabinetId(cabinetId).entityType("patient")
            .entityId(patientId).operation("CREATE").userId(userId).userRole("DOCTOR")
            .timestamp(now).build();

        try (var ignored = TenantContext.open(cabinetId, "cabinet00000"))
        {
            service.recordEvent(event);
        }

        verify(auditRepository).save(argThat(saved ->
            cabinetId.equals(saved.cabinetId())
                && userId.equals(saved.userId())
                && "OWNER".equals(saved.userRole())));
    }

    @Test
    @DisplayName("Event rejected when tenant context not active")
    void eventRejectedWithoutTenantContext()
    {
        when(currentAccount.requireId()).thenReturn(userId);
        AuditEvent event = new AuditEventBuilder().cabinetId(cabinetId).entityType("patient")
            .entityId(patientId).operation("CREATE").userId(userId).userRole("DOCTOR")
            .timestamp(now).build();

        // No tenant context active
        assertThrows(IllegalStateException.class, () -> service.recordEvent(event));
        verify(auditRepository, never()).save(any());
        verifyNoInteractions(memberships);
    }

    @Test
    @DisplayName("Event rejected when cabinet mismatches active tenant")
    void eventRejectedWithCabinetMismatch()
    {
        UUID otherCabinet = UUID.randomUUID();
        when(currentAccount.requireId()).thenReturn(userId);
        AuditEvent event = new AuditEventBuilder().cabinetId(otherCabinet).entityType("patient")
            .entityId(patientId).operation("CREATE").userId(userId).userRole("DOCTOR")
            .timestamp(now).build();

        try (var ignored = TenantContext.open(cabinetId, "cabinet00000"))
        {
            assertThrows(IllegalArgumentException.class, () -> service.recordEvent(event));
        }

        verify(auditRepository, never()).save(any());
        verifyNoInteractions(memberships);
    }

    @Test
    @DisplayName("Event rejected when user mismatches authenticated user")
    void eventRejectedWithUserMismatch()
    {
        UUID otherUser = UUID.randomUUID();
        when(currentAccount.requireId()).thenReturn(userId);
        AuditEvent event = new AuditEventBuilder().cabinetId(cabinetId).entityType("patient")
            .entityId(patientId).operation("CREATE").userId(otherUser).userRole("DOCTOR")
            .timestamp(now).build();

        try (var ignored = TenantContext.open(cabinetId, "cabinet00000"))
        {
            assertThrows(IllegalArgumentException.class, () -> service.recordEvent(event));
        }

        verify(auditRepository, never()).save(any());
        verifyNoInteractions(memberships);
    }

    @Test
    @DisplayName("Cross-cabinet audit writes prevented by tenant mismatch")
    void crossCabinetAuditWritesPrevented()
    {
        UUID cabinet1 = UUID.randomUUID();
        UUID cabinet2 = UUID.randomUUID();
        when(currentAccount.requireId()).thenReturn(userId);

        // Event targets cabinet2 but active context is cabinet1
        AuditEvent event = new AuditEventBuilder().cabinetId(cabinet2).entityType("patient")
            .entityId(patientId).operation("CREATE").userId(userId).userRole("DOCTOR")
            .timestamp(now).build();

        try (var ignored = TenantContext.open(cabinet1, "cabinet00000"))
        {
            assertThrows(IllegalArgumentException.class, () -> service.recordEvent(event));
        }

        verify(auditRepository, never()).save(any());
        verifyNoInteractions(memberships);
    }

    @Test
    @DisplayName("Convenience method builds and records event with correct cabinet and user")
    void convenienceMethodBuildsEvent()
    {
        when(currentAccount.requireId()).thenReturn(userId);

        try (var ignored = TenantContext.open(cabinetId, "cabinet00000"))
        {
            service.recordEvent(cabinetId, "patient", patientId, "CREATE", "SYSTEM");
        }

        verify(auditRepository).save(argThat(event ->
            event.cabinetId().equals(cabinetId)
                && event.userId().equals(userId)
                && "patient".equals(event.entityType())
                && event.entityId().equals(patientId)
                && "CREATE".equals(event.operation())
                && "OWNER".equals(event.userRole())));
    }

    @Test
    @DisplayName("Builder requires cabinetId")
    void builderRequiresCabinetId()
    {
        assertThrows(IllegalArgumentException.class,
                () -> new AuditEventBuilder().entityType("patient").entityId(patientId)
                    .operation("CREATE").userId(userId).timestamp(now).build());
    }

    @Test
    @DisplayName("Builder requires entityType")
    void builderRequiresEntityType()
    {
        assertThrows(IllegalArgumentException.class,
                () -> new AuditEventBuilder().cabinetId(cabinetId).entityId(patientId)
                    .operation("CREATE").userId(userId).timestamp(now).build());
    }

    @Test
    @DisplayName("Builder requires entityId")
    void builderRequiresEntityId()
    {
        assertThrows(IllegalArgumentException.class,
                () -> new AuditEventBuilder().cabinetId(cabinetId).entityType("patient")
                    .operation("CREATE").userId(userId).timestamp(now).build());
    }

    @Test
    @DisplayName("Builder requires operation")
    void builderRequiresOperation()
    {
        assertThrows(IllegalArgumentException.class,
                () -> new AuditEventBuilder().cabinetId(cabinetId).entityType("patient")
                    .entityId(patientId).userId(userId).timestamp(now).build());
    }

    @Test
    @DisplayName("Builder requires userId")
    void builderRequiresUserId()
    {
        assertThrows(IllegalArgumentException.class,
                () -> new AuditEventBuilder().cabinetId(cabinetId).entityType("patient")
                    .entityId(patientId).operation("CREATE").timestamp(now).build());
    }

    @Test
    @DisplayName("Builder requires timestamp")
    void builderRequiresTimestamp()
    {
        assertThrows(IllegalArgumentException.class,
                () -> new AuditEventBuilder().cabinetId(cabinetId).entityType("patient")
                    .entityId(patientId).operation("CREATE").userId(userId).build());
    }

    @Test
    @DisplayName("Standalone VIEW persists IP but drops untrusted user agent")
    void eventPreservesOptionalFields()
    {
        when(currentAccount.requireId()).thenReturn(userId);
        AuditEvent event = new AuditEventBuilder().cabinetId(cabinetId).entityType("patient")
            .entityId(patientId).operation("VIEW").userId(userId).userRole("DOCTOR")
            .timestamp(now).ipAddress("192.168.1.1").userAgent("Mozilla/5.0").build();

        try (var ignored = TenantContext.open(cabinetId, "cabinet00000"))
        {
            service.recordEvent(event);
        }

        verify(auditRepository).save(argThat(saved ->
            "192.168.1.1".equals(saved.ipAddress())
                && saved.userAgent() == null));
    }

    @Test
    @DisplayName("All valid operations are accepted by the builder")
    void allValidOperationsAccepted()
    {
        when(currentAccount.requireId()).thenReturn(userId);
        String[] ops = {"CREATE", "UPDATE", "DELETE", "VIEW", "EXPORT", "PRINT", "SHARE"};

        for (String operation : ops)
        {
            AuditEvent event = new AuditEventBuilder().cabinetId(cabinetId).entityType("patient")
                .entityId(patientId).operation(operation).userId(userId).userRole("DOCTOR")
                .timestamp(now).build();

            try (var ignored = TenantContext.open(cabinetId, "cabinet00000"))
            {
                service.recordEvent(event);
            }
        }

        verify(auditRepository, times(ops.length)).save(any());
    }

    @Test
    @DisplayName("No sensitive payload fields are set by basic event construction")
    void noSensitivePayloadInEventMetadata()
    {
        AuditEvent event = new AuditEventBuilder().cabinetId(cabinetId).entityType("patient")
            .entityId(patientId).operation("CREATE").userId(userId).userRole("DOCTOR")
            .timestamp(now).build();

        // JSONB payload fields must be null unless explicitly set
        assertNull(event.changedFields(), "changedFields must not be populated by default");
        assertNull(event.previousValues(), "previousValues must not be populated by default");
        assertNull(event.newValues(), "newValues must not be populated by default");
        assertNull(event.metadata(), "metadata must not be populated by default");
    }

    @Test
    @DisplayName("Spoofed owner role is overridden by trusted doctor membership")
    void spoofedRoleOverriddenByDoctorMembership()
    {
        when(currentAccount.requireId()).thenReturn(userId);
        stubActiveMember("DOCTOR");
        AuditEvent event = validEventBuilder().userRole("OWNER").build();

        try (var ignored = TenantContext.open(cabinetId, "cabinet00000"))
        {
            service.recordEvent(event);
        }

        verify(auditRepository).save(argThat(saved -> "DOCTOR".equals(saved.userRole())));
    }

    @Test
    @DisplayName("Secretary membership never inherits a caller's doctor role")
    void spoofedRoleOverriddenBySecretaryMembership()
    {
        when(currentAccount.requireId()).thenReturn(userId);
        stubActiveMember("SECRETARY");
        AuditEvent event = validEventBuilder().userRole("DOCTOR").build();

        try (var ignored = TenantContext.open(cabinetId, "cabinet00000"))
        {
            service.recordEvent(event);
        }

        verify(auditRepository).save(argThat(saved -> "SECRETARY".equals(saved.userRole())));
    }

    @Test
    @DisplayName("Trusted MEMBER fallback never inherits a privileged caller role")
    void trustedMemberFallbackOverridesCallerRole()
    {
        when(currentAccount.requireId()).thenReturn(userId);
        stubActiveMember("MEMBER");
        AuditEvent event = validEventBuilder().userRole("OWNER").build();

        try (var ignored = TenantContext.open(cabinetId, "cabinet00000"))
        {
            service.recordEvent(event);
        }

        verify(auditRepository).save(argThat(saved -> "MEMBER".equals(saved.userRole())));
    }

    @Test
    @DisplayName("Missing active membership prevents audit persistence")
    void missingActiveMembershipRejected()
    {
        when(currentAccount.requireId()).thenReturn(userId);
        when(memberships.findActiveRole(cabinetId, userId)).thenReturn(Optional.empty());
        AuditEvent event = validEventBuilder().build();

        try (var ignored = TenantContext.open(cabinetId, "cabinet00000"))
        {
            assertThrows(IllegalStateException.class, () -> service.recordEvent(event));
        }

        verify(auditRepository, never()).save(any());
    }

    @Test
    @DisplayName("Inactive membership excluded by the trusted query prevents audit persistence")
    void inactiveMembershipRejected()
    {
        when(currentAccount.requireId()).thenReturn(userId);
        // The trusted platform query excludes inactive memberships.
        when(memberships.findActiveRole(cabinetId, userId)).thenReturn(Optional.empty());
        AuditEvent event = validEventBuilder().build();

        try (var ignored = TenantContext.open(cabinetId, "cabinet00000"))
        {
            assertThrows(IllegalStateException.class, () -> service.recordEvent(event));
        }

        verify(auditRepository, never()).save(any());
    }

    @Test
    @DisplayName("Direct metadata setter cannot bypass sensitive payload validation")
    void directSensitiveMetadataSetterRejected()
    {
        when(currentAccount.requireId()).thenReturn(userId);
        AuditEvent event = validEventBuilder().build();
        event.setMetadata("{\"diagnosis\":\"synthetic-clinical-canary\"}");

        try (var ignored = TenantContext.open(cabinetId, "cabinet00000"))
        {
            assertThrows(IllegalArgumentException.class, () -> service.recordEvent(event));
        }

        verify(auditRepository, never()).save(any());
    }

    @Test
    @DisplayName("Changed fields cannot contain sensitive values assigned through a setter")
    void directSensitiveChangedFieldsSetterRejected()
    {
        when(currentAccount.requireId()).thenReturn(userId);
        AuditEvent event = validEventBuilder().operation("UPDATE").build();
        event.setChangedFields("[\"diagnosis\",\"synthetic-clinical-canary\"]");

        try (var ignored = TenantContext.open(cabinetId, "cabinet00000"))
        {
            assertThrows(IllegalArgumentException.class, () -> service.recordEvent(event));
        }

        verify(auditRepository, never()).save(any());
    }

    @Test
    @DisplayName("Clinical previous values assigned through a setter are rejected")
    void directClinicalPreviousValuesRejected()
    {
        when(currentAccount.requireId()).thenReturn(userId);
        AuditEvent event = validEventBuilder().operation("UPDATE").build();
        event.setPreviousValues("{\"diagnosis\":\"synthetic-clinical-canary\"}");

        try (var ignored = TenantContext.open(cabinetId, "cabinet00000"))
        {
            assertThrows(IllegalArgumentException.class, () -> service.recordEvent(event));
        }

        verify(auditRepository, never()).save(any());
    }

    @Test
    @DisplayName("Clinical new values assigned through a setter are rejected")
    void directClinicalNewValuesRejected()
    {
        when(currentAccount.requireId()).thenReturn(userId);
        AuditEvent event = validEventBuilder().operation("UPDATE").build();
        event.setNewValues("{\"clinical_notes\":\"synthetic-clinical-canary\"}");

        try (var ignored = TenantContext.open(cabinetId, "cabinet00000"))
        {
            assertThrows(IllegalArgumentException.class, () -> service.recordEvent(event));
        }

        verify(auditRepository, never()).save(any());
    }

    @Test
    @DisplayName("Both recording APIs reject missing authentication before membership lookup")
    void missingAuthenticationRejected()
    {
        when(currentAccount.requireId()).thenThrow(
                new AuthenticationCredentialsNotFoundException("Authentication is required"));
        AuditEvent event = validEventBuilder().build();

        try (var ignored = TenantContext.open(cabinetId, "cabinet00000"))
        {
            assertThrows(AuthenticationCredentialsNotFoundException.class, () -> service.recordEvent(event));
            assertThrows(AuthenticationCredentialsNotFoundException.class,
                    () -> service.recordEvent(cabinetId, "patient", patientId, "CREATE", "OWNER"));
        }

        verify(auditRepository, never()).save(any());
        verifyNoInteractions(memberships);
    }

    @Test
    @DisplayName("Builder rejects sensitive metadata")
    void builderSensitiveMetadataRejected()
    {
        assertThrows(IllegalArgumentException.class,
                () -> validEventBuilder().metadata("{\"diagnosis\":\"synthetic-clinical-canary\"}").build());
    }

    @Test
    @DisplayName("Builder rejects sensitive values in changed fields")
    void builderSensitiveChangedFieldsRejected()
    {
        assertThrows(IllegalArgumentException.class,
                () -> validEventBuilder().operation("UPDATE")
                    .changedFields("[\"diagnosis\",\"synthetic-clinical-canary\"]").build());
    }

    @Test
    @DisplayName("Builder rejects clinical previous values")
    void builderClinicalPreviousValuesRejected()
    {
        assertThrows(IllegalArgumentException.class,
                () -> validEventBuilder().operation("UPDATE")
                    .previousValues("{\"diagnosis\":\"synthetic-clinical-canary\"}").build());
    }

    @Test
    @DisplayName("Builder rejects clinical new values")
    void builderClinicalNewValuesRejected()
    {
        assertThrows(IllegalArgumentException.class,
                () -> validEventBuilder().operation("UPDATE")
                    .newValues("{\"clinical_notes\":\"synthetic-clinical-canary\"}").build());
    }

    @Test
    @DisplayName("Field names survive but claimed correlation is replaced by server metadata")
    void safeChangeTrackingAndTrustedMetadataRecorded()
    {
        when(currentAccount.requireId()).thenReturn(userId);
        String changedFields = "[\"diagnosis\",\"clinical_notes\"]";
        String metadata = "{\"requestId\":\"00000000-0000-0000-0000-000000000001\",\"outcome\":\"SUCCESS\"}";
        String trustedRequestId = "00000000-0000-0000-0000-000000000002";
        AuditEvent event = validEventBuilder().operation("UPDATE")
            .changedFields(changedFields).metadata(metadata).build();
        String previousRequestId = MDC.get("requestId");

        try (var ignored = TenantContext.open(cabinetId, "cabinet00000"))
        {
            MDC.put("requestId", trustedRequestId);
            service.recordEvent(event);
        } finally
        {
            if (previousRequestId == null)
            {
                MDC.remove("requestId");
            } else
            {
                MDC.put("requestId", previousRequestId);
            }
        }

        String expectedMetadata = "{\"outcome\":\"SUCCESS\",\"requestId\":\"" + trustedRequestId + "\"}";
        verify(auditRepository).save(argThat(saved ->
            changedFields.equals(saved.changedFields())
                && expectedMetadata.equals(saved.metadata())
                && saved.previousValues() == null
                && saved.newValues() == null));
    }

    private AuditEventBuilder validEventBuilder()
    {
        return new AuditEventBuilder().cabinetId(cabinetId).entityType("patient")
            .entityId(patientId).operation("CREATE").userId(userId).userRole("DOCTOR").timestamp(now);
    }

    private void stubActiveMember(String role)
    {
        when(memberships.findActiveRole(cabinetId, userId)).thenReturn(Optional.of(role));
    }

    /** Minimal transaction manager retaining Spring's real transaction and synchronization lifecycle. */
    private static final class UnitTransactionManager extends AbstractPlatformTransactionManager
    {
        @Override
        protected Object doGetTransaction()
        {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition)
        {
            // No database resource is needed for these unit tests.
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status)
        {
            // Persistence is represented by the mocked append-only repository.
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status)
        {
            // No database resource is held by this fake.
        }
    }
}
