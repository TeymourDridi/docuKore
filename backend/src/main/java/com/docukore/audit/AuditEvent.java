package com.docukore.audit;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.ColumnTransformer;
import org.hibernate.annotations.Immutable;

/**
 * Audit log entry for tenant operations.
 *
 * Records who performed what action on which resource in which cabinet,
 * including timestamps, context, and optional change tracking.
 *
 * Sensitive medical information (names, diagnoses, clinical notes,
 * prescriptions, document contents) must never be stored in audit fields.
 *
 * JSON documents use explicit PostgreSQL casts while remaining String-valued; no extra JSON mapper is
 * needed for persistence. Legacy previous/new columns remain readable, but the recording service rejects
 * all clinical value payloads. Hibernate dirty updates are disabled; the repository exposes insert only.
 */
@Entity
@Immutable
@Table(name = "audit_logs")
public class AuditEvent
{

    /** Immutable event identifier. */
    @Id
    private UUID id;

    /** Cabinet association checked against the active tenant. */
    @Column(name = "cabinet_id", nullable = false)
    private UUID cabinetId;

    /** Fixed resource category. */
    @Column(name = "entity_type", nullable = false, length = 100)
    private String entityType;

    /** Opaque resource identifier. */
    @Column(name = "entity_id", nullable = false)
    private UUID entityId;

    /** Supported audit operation. */
    @Column(name = "operation", nullable = false, length = 50)
    private String operation;

    /** Authenticated actor identifier. */
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /** Server-maintained active cabinet role. */
    @Column(name = "user_role", length = 50)
    private String userRole;

    /** Event instant with PostgreSQL microsecond precision. */
    @Column(name = "timestamp", nullable = false)
    private OffsetDateTime timestamp;

    /** Optional server-observed IP address. */
    @ColumnTransformer(write = "cast(? as inet)")
    @Column(name = "ip_address", columnDefinition = "inet")
    private String ipAddress;

    /** Legacy optional header, not populated by the recording service. */
    @Column(name = "user_agent")
    private String userAgent;

    /** Allowlisted changed field names, without values. */
    @ColumnTransformer(write = "cast(? as jsonb)")
    @Column(name = "changed_fields", columnDefinition = "jsonb")
    private String changedFields;

    /** Legacy JSON column readable for compatibility, rejected on recording. */
    @ColumnTransformer(write = "cast(? as jsonb)")
    @Column(name = "previous_values", columnDefinition = "jsonb")
    private String previousValues;

    /** Legacy JSON column readable for compatibility, rejected on recording. */
    @ColumnTransformer(write = "cast(? as jsonb)")
    @Column(name = "new_values", columnDefinition = "jsonb")
    private String newValues;

    /** Restricted opaque correlation and fixed outcome context. */
    @ColumnTransformer(write = "cast(? as jsonb)")
    @Column(name = "metadata", columnDefinition = "jsonb")
    private String metadata;

    protected AuditEvent()
    {
    }

    /**
     * Create an audit event.
     *
     * @param id the event UUID
     * @param cabinetId the cabinet UUID
     * @param entityType the resource type
     * @param entityId the resource UUID
     * @param operation the action
     * @param userId the authenticated user UUID
     * @param userRole the user's role
     * @param timestamp the event timestamp
     */
    public AuditEvent(UUID id, UUID cabinetId, String entityType, UUID entityId,
            String operation, UUID userId, String userRole, OffsetDateTime timestamp)
    {
        this.id = id;
        this.cabinetId = cabinetId;
        this.entityType = entityType;
        this.entityId = entityId;
        this.operation = operation;
        this.userId = userId;
        this.userRole = userRole;
        this.timestamp = timestamp;
    }

    public UUID id()
    {
        return id;
    }

    public UUID cabinetId()
    {
        return cabinetId;
    }

    public String entityType()
    {
        return entityType;
    }

    public UUID entityId()
    {
        return entityId;
    }

    public String operation()
    {
        return operation;
    }

    public UUID userId()
    {
        return userId;
    }

    public String userRole()
    {
        return userRole;
    }

    public OffsetDateTime timestamp()
    {
        return timestamp;
    }

    public String ipAddress()
    {
        return ipAddress;
    }

    public void setIpAddress(String ipAddress)
    {
        this.ipAddress = ipAddress;
    }

    public String userAgent()
    {
        return userAgent;
    }

    public void setUserAgent(String userAgent)
    {
        this.userAgent = userAgent;
    }

    public String changedFields()
    {
        return changedFields;
    }

    public void setChangedFields(String changedFields)
    {
        this.changedFields = changedFields;
    }

    public String previousValues()
    {
        return previousValues;
    }

    public void setPreviousValues(String previousValues)
    {
        this.previousValues = previousValues;
    }

    public String newValues()
    {
        return newValues;
    }

    public void setNewValues(String newValues)
    {
        this.newValues = newValues;
    }

    public String metadata()
    {
        return metadata;
    }

    public void setMetadata(String metadata)
    {
        this.metadata = metadata;
    }
}
