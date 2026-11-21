-- V5: Create import infrastructure tables
-- Support for legacy data migration and import tracking

-- Import batches (top-level import operation)
CREATE TABLE import_batches (
    id UUID PRIMARY KEY,
    cabinet_id UUID NOT NULL,
    source_system_id UUID NOT NULL,
    batch_label VARCHAR(255) NOT NULL,
    import_type VARCHAR(50) NOT NULL,
    status VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    started_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    initiated_by UUID NOT NULL,
    total_records INTEGER DEFAULT 0,
    processed_records INTEGER DEFAULT 0,
    successful_records INTEGER DEFAULT 0,
    failed_records INTEGER DEFAULT 0,
    skipped_records INTEGER DEFAULT 0,
    metadata JSONB,
    CONSTRAINT valid_import_type CHECK (
        import_type IN ('PATIENT_IDENTITY', 'MEDICAL_RECORDS', 'APPOINTMENTS',
                        'PRESCRIPTIONS', 'DOCUMENTS', 'FULL_MIGRATION')
    ),
    CONSTRAINT valid_status CHECK (
        status IN ('PENDING', 'RUNNING', 'COMPLETED', 'FAILED', 'CANCELLED')
    )
);

CREATE INDEX idx_import_batches_cabinet ON import_batches(cabinet_id, started_at DESC);
CREATE INDEX idx_import_batches_status ON import_batches(cabinet_id, status);
CREATE INDEX idx_import_batches_source ON import_batches(source_system_id, started_at DESC);

-- Import staging records (raw imported data before matching)
CREATE TABLE import_staging_records (
    id UUID PRIMARY KEY,
    batch_id UUID NOT NULL REFERENCES import_batches(id) ON DELETE CASCADE,
    source_record_id VARCHAR(255) NOT NULL,
    record_type VARCHAR(50) NOT NULL,
    raw_data JSONB NOT NULL,
    normalized_data JSONB,
    processing_status VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    matched_entity_type VARCHAR(50),
    matched_entity_id UUID,
    match_confidence VARCHAR(50),
    match_method VARCHAR(50),
    processed_at TIMESTAMPTZ,
    error_message TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT valid_processing_status CHECK (
        processing_status IN ('PENDING', 'MATCHED', 'CREATED', 'UPDATED', 'FAILED', 'SKIPPED')
    ),
    CONSTRAINT valid_match_confidence CHECK (
        match_confidence IS NULL OR match_confidence IN ('EXACT', 'HIGH', 'MEDIUM', 'LOW', 'MANUAL')
    ),
    UNIQUE(batch_id, source_record_id)
);

CREATE INDEX idx_import_staging_batch ON import_staging_records(batch_id, processing_status);
CREATE INDEX idx_import_staging_matched ON import_staging_records(matched_entity_type, matched_entity_id)
    WHERE matched_entity_id IS NOT NULL;

-- Import source snapshots (encrypted backups of original files)
CREATE TABLE import_source_snapshots (
    id UUID PRIMARY KEY,
    batch_id UUID NOT NULL REFERENCES import_batches(id) ON DELETE RESTRICT,
    file_name VARCHAR(255) NOT NULL,
    file_size_bytes BIGINT NOT NULL,
    file_hash VARCHAR(64) NOT NULL,
    encryption_key_id VARCHAR(255) NOT NULL,
    storage_location TEXT NOT NULL,
    mime_type VARCHAR(100),
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ,
    CONSTRAINT positive_file_size CHECK (file_size_bytes > 0)
);

CREATE INDEX idx_import_source_snapshots_batch ON import_source_snapshots(batch_id);

-- Import candidate matches (manual resolution queue)
CREATE TABLE import_candidate_matches (
    id UUID PRIMARY KEY,
    staging_record_id UUID NOT NULL REFERENCES import_staging_records(id) ON DELETE CASCADE,
    candidate_entity_type VARCHAR(50) NOT NULL,
    candidate_entity_id UUID NOT NULL,
    match_score NUMERIC(5,2) NOT NULL,
    match_reasons JSONB,
    resolution_status VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    resolved_by UUID,
    resolved_at TIMESTAMPTZ,
    resolution_action VARCHAR(50),
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT valid_match_score CHECK (match_score >= 0 AND match_score <= 100),
    CONSTRAINT valid_resolution_status CHECK (
        resolution_status IN ('PENDING', 'ACCEPTED', 'REJECTED', 'SKIPPED')
    ),
    CONSTRAINT valid_resolution_action CHECK (
        resolution_action IS NULL OR resolution_action IN ('MERGE', 'CREATE_NEW', 'SKIP')
    )
);

CREATE INDEX idx_import_candidate_matches_staging ON import_candidate_matches(staging_record_id);
CREATE INDEX idx_import_candidate_matches_pending ON import_candidate_matches(resolution_status)
    WHERE resolution_status = 'PENDING';

-- Import events (detailed import activity log)
CREATE TABLE import_events (
    id UUID PRIMARY KEY,
    batch_id UUID NOT NULL REFERENCES import_batches(id) ON DELETE CASCADE,
    staging_record_id UUID REFERENCES import_staging_records(id) ON DELETE CASCADE,
    event_type VARCHAR(50) NOT NULL,
    event_timestamp TIMESTAMPTZ NOT NULL,
    severity VARCHAR(50) NOT NULL DEFAULT 'INFO',
    message TEXT NOT NULL,
    details JSONB,
    CONSTRAINT valid_event_type CHECK (
        event_type IN ('BATCH_START', 'BATCH_COMPLETE', 'RECORD_PROCESSED',
                       'MATCH_FOUND', 'MANUAL_REVIEW_REQUIRED', 'ERROR', 'WARNING')
    ),
    CONSTRAINT valid_severity CHECK (
        severity IN ('INFO', 'WARNING', 'ERROR', 'CRITICAL')
    )
);

CREATE INDEX idx_import_events_batch ON import_events(batch_id, event_timestamp DESC);
CREATE INDEX idx_import_events_severity ON import_events(batch_id, severity, event_timestamp DESC)
    WHERE severity IN ('ERROR', 'CRITICAL');

-- Import errors (redacted error log)
CREATE TABLE import_errors (
    id UUID PRIMARY KEY,
    batch_id UUID NOT NULL REFERENCES import_batches(id) ON DELETE CASCADE,
    staging_record_id UUID REFERENCES import_staging_records(id) ON DELETE CASCADE,
    error_code VARCHAR(50) NOT NULL,
    error_message TEXT NOT NULL,
    error_context JSONB,
    occurred_at TIMESTAMPTZ NOT NULL,
    retry_count INTEGER DEFAULT 0,
    resolved BOOLEAN DEFAULT false,
    resolved_at TIMESTAMPTZ
);

CREATE INDEX idx_import_errors_batch ON import_errors(batch_id, occurred_at DESC);
CREATE INDEX idx_import_errors_unresolved ON import_errors(batch_id, resolved)
    WHERE resolved = false;
