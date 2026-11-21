-- V3: Create staff profiles and medical safety tables
-- Staff identity and critical medical safety information

-- Staff profiles within tenant cabinet
CREATE TABLE staff_profiles (
    id UUID PRIMARY KEY,
    cabinet_id UUID NOT NULL,
    user_id UUID NOT NULL,
    staff_role VARCHAR(50) NOT NULL,
    professional_license_number VARCHAR(100),
    license_issuing_country VARCHAR(3),
    license_expiry_date DATE,
    specialization VARCHAR(255),
    professional_title VARCHAR(255),
    is_active BOOLEAN DEFAULT true,
    activated_at TIMESTAMPTZ,
    deactivated_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT valid_staff_role CHECK (
        staff_role IN ('DOCTOR', 'SECRETARY', 'NURSE', 'TECHNICIAN', 'OTHER')
    ),
    UNIQUE(cabinet_id, user_id)
);

CREATE INDEX idx_staff_profiles_cabinet_id ON staff_profiles(cabinet_id);
CREATE INDEX idx_staff_profiles_user_id ON staff_profiles(user_id);
CREATE INDEX idx_staff_profiles_active ON staff_profiles(cabinet_id, is_active) WHERE is_active = true;

-- Patient allergies (drug, food, environmental)
CREATE TABLE patient_allergies (
    id UUID PRIMARY KEY,
    patient_id UUID NOT NULL REFERENCES patients(id) ON DELETE CASCADE,
    allergen_type VARCHAR(50) NOT NULL,
    allergen_name VARCHAR(255) NOT NULL,
    reaction_severity VARCHAR(50) NOT NULL,
    reaction_description TEXT,
    onset_date DATE,
    verified_by UUID,
    verified_at TIMESTAMPTZ,
    is_active BOOLEAN DEFAULT true,
    notes TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    created_by UUID,
    CONSTRAINT valid_allergen_type CHECK (
        allergen_type IN ('DRUG', 'FOOD', 'ENVIRONMENTAL', 'OTHER')
    ),
    CONSTRAINT valid_reaction_severity CHECK (
        reaction_severity IN ('MILD', 'MODERATE', 'SEVERE', 'LIFE_THREATENING')
    )
);

CREATE INDEX idx_patient_allergies_patient_id ON patient_allergies(patient_id);
CREATE INDEX idx_patient_allergies_active ON patient_allergies(patient_id, is_active) WHERE is_active = true;
CREATE INDEX idx_patient_allergies_severity ON patient_allergies(patient_id, reaction_severity)
    WHERE is_active = true AND reaction_severity IN ('SEVERE', 'LIFE_THREATENING');

-- Medical alerts (warnings, precautions, critical information)
CREATE TABLE patient_medical_alerts (
    id UUID PRIMARY KEY,
    patient_id UUID NOT NULL REFERENCES patients(id) ON DELETE CASCADE,
    alert_type VARCHAR(50) NOT NULL,
    alert_code VARCHAR(100),
    alert_description TEXT NOT NULL,
    severity VARCHAR(50) NOT NULL,
    effective_from TIMESTAMPTZ NOT NULL,
    effective_until TIMESTAMPTZ,
    is_active BOOLEAN DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL,
    created_by UUID NOT NULL,
    CONSTRAINT valid_alert_type CHECK (
        alert_type IN ('CONTRAINDICATION', 'PRECAUTION', 'INTERACTION_WARNING',
                       'PROCEDURE_RESTRICTION', 'INFECTIOUS', 'OTHER')
    ),
    CONSTRAINT valid_severity CHECK (
        severity IN ('INFO', 'WARNING', 'CRITICAL')
    )
);

CREATE INDEX idx_patient_medical_alerts_patient_id ON patient_medical_alerts(patient_id);
CREATE INDEX idx_patient_medical_alerts_active ON patient_medical_alerts(patient_id, is_active)
    WHERE is_active = true;
CREATE INDEX idx_patient_medical_alerts_critical ON patient_medical_alerts(patient_id, severity)
    WHERE is_active = true AND severity = 'CRITICAL';
