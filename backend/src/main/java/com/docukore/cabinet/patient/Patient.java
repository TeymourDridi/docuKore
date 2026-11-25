package com.docukore.cabinet.patient;

import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Patient record in the trusted cabinet's tenant schema.
 * Birth information is replaced atomically and audit timestamps use an explicit clock.
 */
@Entity
@Table(name = "patients")
public class Patient
{
    /** Patient identifier. */
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** Cabinet identifier. */
    @Column(name = "cabinet_id", nullable = false, updatable = false)
    private UUID cabinetId;

    /** Patient number within the cabinet. */
    @Column(name = "patient_number", nullable = false, length = 50, updatable = false)
    private String patientNumber;

    /** Legal given name. */
    @Column(name = "legal_given_name", nullable = false, length = 255)
    private String legalGivenName;

    /** Legal family name. */
    @Column(name = "legal_family_name", nullable = false, length = 255)
    private String legalFamilyName;

    /** Birth date with precision. */
    @Embedded
    private BirthDate birthDate;

    /** Optional initial note; length validation belongs to the application. */
    @Column(name = "initial_note", columnDefinition = "text")
    private String initialNote;

    /** Legacy source system identifier. */
    @Column(name = "legacy_source_system_id")
    private UUID legacySourceSystemId;

    /** Legacy record identifier. */
    @Column(name = "legacy_record_id", length = 255)
    private String legacyRecordId;

    /** Import batch identifier. */
    @Column(name = "import_batch_id")
    private UUID importBatchId;

    /** Creation timestamp. */
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Last update timestamp. */
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    // Applied SQL permits null on historical/imported rows. New construction always
    // requires a trusted creator; do not misrepresent the existing SQL as NOT NULL.
    /** Creator identifier. */
    @Column(name = "created_by", nullable = true, updatable = false)
    private UUID createdBy;

    /** Last updater identifier. */
    @Column(name = "updated_by")
    private UUID updatedBy;

    /** Archive timestamp. */
    @Column(name = "archived_at")
    private Instant archivedAt;

    /** Required by JPA field access, not an application creation path. */
    protected Patient()
    {
    }

    /**
     * Creates a patient with unknown birth information and one creation instant.
     * Cabinet, patient number and creator must be supplied by trusted backend
     * orchestration, never accepted as client-controlled authorization metadata.
     * This entity does not itself authenticate or authorize that caller.
     *
     * @param id              the patient identifier
     * @param cabinetId       the cabinet identifier
     * @param patientNumber   the patient number
     * @param legalGivenName  the legal given name
     * @param legalFamilyName the legal family name
     * @param createdBy       the creator identifier
     * @param clock           the clock for timestamp generation
     */
    public Patient(UUID id, UUID cabinetId, String patientNumber, String legalGivenName,
                   String legalFamilyName, UUID createdBy, Clock clock)
    {
        this.id = Objects.requireNonNull(id, "id");
        this.cabinetId = Objects.requireNonNull(cabinetId, "cabinetId");
        this.patientNumber = Objects.requireNonNull(patientNumber, "patientNumber");
        this.legalGivenName = Objects.requireNonNull(legalGivenName, "legalGivenName");
        this.legalFamilyName = Objects.requireNonNull(legalFamilyName, "legalFamilyName");
        this.createdBy = Objects.requireNonNull(createdBy, "createdBy");
        this.updatedBy = this.createdBy;
        this.birthDate = BirthDate.unknown();
        Instant now = instant(clock);
        this.createdAt = now;
        this.updatedAt = now;
    }

    public UUID getId()
    {
        return id;
    }

    public UUID getCabinetId()
    {
        return cabinetId;
    }

    public String getPatientNumber()
    {
        return patientNumber;
    }

    public String getLegalGivenName()
    {
        return legalGivenName;
    }

    public void setLegalGivenName(String legalGivenName, Clock clock)
    {
        Objects.requireNonNull(legalGivenName, "legalGivenName");
        Instant now = instant(clock);
        this.legalGivenName = legalGivenName;
        this.updatedAt = now;
    }

    public String getLegalFamilyName()
    {
        return legalFamilyName;
    }

    public void setLegalFamilyName(String legalFamilyName, Clock clock)
    {
        Objects.requireNonNull(legalFamilyName, "legalFamilyName");
        Instant now = instant(clock);
        this.legalFamilyName = legalFamilyName;
        this.updatedAt = now;
    }

    public BirthDate getBirthDate()
    {
        return birthDate;
    }

    public void setBirthDate(BirthDate birthDate, Clock clock)
    {
        Objects.requireNonNull(birthDate, "birthDate");
        Instant now = instant(clock);
        this.birthDate = birthDate;
        this.updatedAt = now;
    }

    public Integer getBirthYear()
    {
        return birthDate.year();
    }

    public Integer getBirthMonth()
    {
        return birthDate.month();
    }

    public Integer getBirthDay()
    {
        return birthDate.day();
    }

    public BirthPrecision getBirthPrecision()
    {
        return birthDate.precision();
    }

    public String getInitialNote()
    {
        return initialNote;
    }

    public void setInitialNote(String initialNote, Clock clock)
    {
        Instant now = instant(clock);
        this.initialNote = initialNote;
        this.updatedAt = now;
    }

    public UUID getLegacySourceSystemId()
    {
        return legacySourceSystemId;
    }

    public void setLegacySourceSystemId(UUID legacySourceSystemId, Clock clock)
    {
        Instant now = instant(clock);
        this.legacySourceSystemId = legacySourceSystemId;
        this.updatedAt = now;
    }

    public String getLegacyRecordId()
    {
        return legacyRecordId;
    }

    public void setLegacyRecordId(String legacyRecordId, Clock clock)
    {
        Instant now = instant(clock);
        this.legacyRecordId = legacyRecordId;
        this.updatedAt = now;
    }

    public UUID getImportBatchId()
    {
        return importBatchId;
    }

    public void setImportBatchId(UUID importBatchId, Clock clock)
    {
        Instant now = instant(clock);
        this.importBatchId = importBatchId;
        this.updatedAt = now;
    }

    public Instant getCreatedAt()
    {
        return createdAt;
    }

    public Instant getUpdatedAt()
    {
        return updatedAt;
    }

    public UUID getCreatedBy()
    {
        return createdBy;
    }

    public UUID getUpdatedBy()
    {
        return updatedBy;
    }

    public void setUpdatedBy(UUID updatedBy, Clock clock)
    {
        Instant now = instant(clock);
        this.updatedBy = updatedBy;
        this.updatedAt = now;
    }

    public Instant getArchivedAt()
    {
        return archivedAt;
    }

    /**
     * Soft archive preserves the row and its original archive instant on retries.
     *
     * @param clock the clock for timestamp generation
     */
    public void archive(Clock clock)
    {
        Objects.requireNonNull(clock, "clock");
        if (archivedAt != null)
        {
            return;
        }
        Instant now = instant(clock);
        this.archivedAt = now;
        this.updatedAt = now;
    }

    public boolean isArchived()
    {
        return archivedAt != null;
    }

    private static Instant instant(Clock clock)
    {
        return Objects.requireNonNull(Objects.requireNonNull(clock, "clock").instant(), "clock instant");
    }
}
