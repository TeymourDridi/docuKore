-- V1: Create patients table (core patient identity)
-- Legal names are canonical; birth date components nullable with precision flag
-- Patient number unique within cabinet; multiple patients can share phone numbers

CREATE TABLE patients (
    id UUID PRIMARY KEY,
    cabinet_id UUID NOT NULL,
    patient_number VARCHAR(50) NOT NULL,
    legal_given_name VARCHAR(255) NOT NULL,
    legal_family_name VARCHAR(255) NOT NULL,
    birth_year INTEGER,
    birth_month INTEGER,
    birth_day INTEGER,
    birth_precision VARCHAR(20) NOT NULL DEFAULT 'UNKNOWN',
    legacy_source_system_id UUID,
    legacy_record_id VARCHAR(255),
    import_batch_id UUID,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    created_by UUID,
    updated_by UUID,
    CONSTRAINT unique_patient_number UNIQUE (cabinet_id, patient_number),
    CONSTRAINT unique_legacy_source UNIQUE (legacy_source_system_id, legacy_record_id),
    CONSTRAINT valid_birth_precision CHECK (
        birth_precision IN ('COMPLETE_DATE', 'YEAR_MONTH', 'YEAR_ONLY', 'UNKNOWN')
    ),
    CONSTRAINT valid_birth_date CHECK (
        (birth_precision = 'COMPLETE_DATE' AND
         birth_year IS NOT NULL AND birth_month IS NOT NULL AND birth_day IS NOT NULL AND
         birth_month BETWEEN 1 AND 12 AND birth_day BETWEEN 1 AND 31 AND
         birth_year >= 1900 AND birth_year <= EXTRACT(YEAR FROM CURRENT_DATE)) OR

        (birth_precision = 'YEAR_MONTH' AND
         birth_year IS NOT NULL AND birth_month IS NOT NULL AND birth_day IS NULL AND
         birth_month BETWEEN 1 AND 12 AND
         birth_year >= 1900 AND birth_year <= EXTRACT(YEAR FROM CURRENT_DATE)) OR

        (birth_precision = 'YEAR_ONLY' AND
         birth_year IS NOT NULL AND birth_month IS NULL AND birth_day IS NULL AND
         birth_year >= 1900 AND birth_year <= EXTRACT(YEAR FROM CURRENT_DATE)) OR

        (birth_precision = 'UNKNOWN' AND
         birth_year IS NULL AND birth_month IS NULL AND birth_day IS NULL)
    )
);

-- Indexes for patient search by name
CREATE INDEX idx_patients_cabinet_given_name ON patients(cabinet_id, legal_given_name);
CREATE INDEX idx_patients_cabinet_family_name ON patients(cabinet_id, legal_family_name);
CREATE INDEX idx_patients_cabinet_patient_number ON patients(cabinet_id, patient_number);
CREATE INDEX idx_patients_legacy_source ON patients(legacy_source_system_id, legacy_record_id) WHERE legacy_source_system_id IS NOT NULL;
CREATE INDEX idx_patients_created_at ON patients(created_at DESC);
