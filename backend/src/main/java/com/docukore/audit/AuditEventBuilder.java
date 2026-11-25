package com.docukore.audit;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Builder for constructing AuditEvent instances safely.
 *
 * Enforces required fields and a narrow payload policy. Optional context is intended only for
 * server-side callers; the service revalidates it and replaces role/metadata claims with trusted values.
 *
 * Usage:
 *
 * AuditEvent event = new AuditEventBuilder()
 *     .cabinetId(cabinetId)
 *     .entityType("patient")
 *     .entityId(patientId)
 *     .operation("CREATE")
 *     .userId(userId)
 *     .userRole("DOCTOR")
 *     .timestamp(now)
 *     .ipAddress(request.getRemoteAddr())
 *     .userAgent(request.getHeader("User-Agent"))
 *     .metadata(metadata)
 *     .build();
 */
public class AuditEventBuilder
{

    /** Generated event identifier. */
    private UUID id = UUID.randomUUID();
    /** Claimed cabinet, validated against tenant context by the service. */
    private UUID cabinetId;
    /** Resource category. */
    private String entityType;
    /** Opaque resource identifier. */
    private UUID entityId;
    /** Supported action. */
    private String operation;
    /** Claimed actor, validated against authentication by the service. */
    private UUID userId;
    /** Legacy role claim, replaced with the trusted membership role. */
    private String userRole;
    /** Event instant. */
    private OffsetDateTime timestamp;
    /** Optional server-observed IP address. */
    private String ipAddress;
    /** Legacy user-agent header, omitted by the recording service. */
    private String userAgent;
    /** Restricted changed field names. */
    private String changedFields;
    /** Legacy snapshot input; any non-null value is rejected. */
    private String previousValues;
    /** Legacy snapshot input; any non-null value is rejected. */
    private String newValues;
    /** Restricted metadata shape; service derives persisted values. */
    private String metadata;

    public AuditEventBuilder id(UUID id)
    {
        this.id = id;
        return this;
    }

    public AuditEventBuilder cabinetId(UUID cabinetId)
    {
        this.cabinetId = cabinetId;
        return this;
    }

    public AuditEventBuilder entityType(String entityType)
    {
        this.entityType = entityType;
        return this;
    }

    public AuditEventBuilder entityId(UUID entityId)
    {
        this.entityId = entityId;
        return this;
    }

    public AuditEventBuilder operation(String operation)
    {
        this.operation = operation;
        return this;
    }

    public AuditEventBuilder userId(UUID userId)
    {
        this.userId = userId;
        return this;
    }

    public AuditEventBuilder userRole(String userRole)
    {
        this.userRole = userRole;
        return this;
    }

    public AuditEventBuilder timestamp(OffsetDateTime timestamp)
    {
        this.timestamp = timestamp;
        return this;
    }

    public AuditEventBuilder ipAddress(String ipAddress)
    {
        this.ipAddress = ipAddress;
        return this;
    }

    public AuditEventBuilder userAgent(String userAgent)
    {
        this.userAgent = userAgent;
        return this;
    }

    public AuditEventBuilder changedFields(String changedFields)
    {
        this.changedFields = changedFields;
        return this;
    }

    public AuditEventBuilder previousValues(String previousValues)
    {
        this.previousValues = previousValues;
        return this;
    }

    public AuditEventBuilder newValues(String newValues)
    {
        this.newValues = newValues;
        return this;
    }

    public AuditEventBuilder metadata(String metadata)
    {
        this.metadata = metadata;
        return this;
    }

    /**
     * Build the AuditEvent with validation.
     *
     * @return a new AuditEvent
     * @throws IllegalArgumentException if required fields are missing
     */
    public AuditEvent build()
    {
        if (cabinetId == null)
        {
            throw new IllegalArgumentException("cabinetId is required");
        }
        if (entityType == null || entityType.isBlank())
        {
            throw new IllegalArgumentException("entityType is required");
        }
        if (entityId == null)
        {
            throw new IllegalArgumentException("entityId is required");
        }
        if (operation == null || operation.isBlank())
        {
            throw new IllegalArgumentException("operation is required");
        }
        if (userId == null)
        {
            throw new IllegalArgumentException("userId is required");
        }
        if (timestamp == null)
        {
            throw new IllegalArgumentException("timestamp is required");
        }

        AuditEvent event = new AuditEvent(id, cabinetId, entityType, entityId, operation,
                userId, userRole, timestamp);
        event.setIpAddress(ipAddress);
        event.setUserAgent(userAgent);
        event.setChangedFields(changedFields);
        event.setPreviousValues(previousValues);
        event.setNewValues(newValues);
        event.setMetadata(metadata);
        AuditPayloadPolicy.validate(event);
        return event;
    }
}
