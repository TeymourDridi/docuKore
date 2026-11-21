-- V2: Create patient identity and contact tables
-- Supports multiple names, identifiers, contacts, addresses, and emergency contacts per patient

-- Alternative names and transliterations (legal names in patients table)
CREATE TABLE patient_names (
    id UUID PRIMARY KEY,
    patient_id UUID NOT NULL REFERENCES patients(id) ON DELETE CASCADE,
    name_type VARCHAR(50) NOT NULL,
    given_name VARCHAR(255) NOT NULL,
    family_name VARCHAR(255) NOT NULL,
    language_tag VARCHAR(35),
    script_code VARCHAR(4),
    notes TEXT,
    is_current BOOLEAN DEFAULT true,
    valid_from DATE,
    valid_until DATE,
    created_at TIMESTAMPTZ NOT NULL,
    created_by UUID,
    CONSTRAINT valid_name_type CHECK (
        name_type IN ('PREFERRED', 'FORMER', 'TRANSLITERATION', 'ALIAS')
    )
);

CREATE INDEX idx_patient_names_patient_id ON patient_names(patient_id);
CREATE INDEX idx_patient_names_current ON patient_names(patient_id, is_current) WHERE is_current = true;

-- Government IDs, passports, insurance numbers
CREATE TABLE patient_identifiers (
    id UUID PRIMARY KEY,
    patient_id UUID NOT NULL REFERENCES patients(id) ON DELETE CASCADE,
    identifier_type VARCHAR(50) NOT NULL,
    identifier_value VARCHAR(255) NOT NULL,
    issuing_country VARCHAR(3),
    issuing_authority VARCHAR(255),
    issue_date DATE,
    expiry_date DATE,
    validation_status VARCHAR(50) NOT NULL DEFAULT 'NOT_VERIFIED',
    verified_by UUID,
    verified_at TIMESTAMPTZ,
    verification_method VARCHAR(100),
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT valid_identifier_type CHECK (
        identifier_type IN ('NATIONAL_ID', 'PASSPORT', 'DRIVER_LICENSE', 'TAX_ID',
                            'INSURANCE_ID', 'SOCIAL_SECURITY', 'MEDICAL_LICENSE', 'OTHER')
    ),
    CONSTRAINT valid_validation_status CHECK (
        validation_status IN ('NOT_VERIFIED', 'VERIFIED', 'EXPIRED', 'INVALID')
    ),
    UNIQUE(patient_id, identifier_type, identifier_value, issuing_country)
);

CREATE INDEX idx_patient_identifiers_patient_id ON patient_identifiers(patient_id);
CREATE INDEX idx_patient_identifiers_value ON patient_identifiers(identifier_type, identifier_value);

-- Administrative gender and pronouns
CREATE TABLE patient_administrative_identity (
    id UUID PRIMARY KEY,
    patient_id UUID NOT NULL REFERENCES patients(id) ON DELETE CASCADE UNIQUE,
    documented_gender VARCHAR(50) NOT NULL DEFAULT 'NOT_SPECIFIED',
    indicated_gender VARCHAR(50) NOT NULL DEFAULT 'NOT_SPECIFIED',
    pronouns VARCHAR(50),
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT valid_documented_gender CHECK (
        documented_gender IN ('MALE', 'FEMALE', 'OTHER', 'NOT_SPECIFIED')
    ),
    CONSTRAINT valid_indicated_gender CHECK (
        indicated_gender IN ('MALE', 'FEMALE', 'OTHER', 'NOT_SPECIFIED')
    )
);

-- Phone, mobile, email, fax (multiple per patient, multiple patients can share phone)
CREATE TABLE patient_contacts (
    id UUID PRIMARY KEY,
    patient_id UUID NOT NULL REFERENCES patients(id) ON DELETE CASCADE,
    contact_type VARCHAR(50) NOT NULL,
    value VARCHAR(255) NOT NULL,
    value_normalized VARCHAR(255),
    is_primary_for_type BOOLEAN DEFAULT false,
    preferred_for_communications BOOLEAN DEFAULT false,
    validation_status VARCHAR(50) NOT NULL DEFAULT 'NOT_VERIFIED',
    verified_at TIMESTAMPTZ,
    is_current BOOLEAN DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT valid_contact_type CHECK (
        contact_type IN ('PHONE', 'MOBILE', 'EMAIL', 'FAX', 'EMERGENCY_PHONE')
    ),
    CONSTRAINT valid_validation_status CHECK (
        validation_status IN ('NOT_VERIFIED', 'VERIFIED', 'INVALID')
    )
);

-- Partial unique index: only one current contact per type/value combination
CREATE UNIQUE INDEX idx_patient_contacts_unique_current ON patient_contacts(patient_id, contact_type, value)
    WHERE is_current = true;

CREATE INDEX idx_patient_contacts_patient_id ON patient_contacts(patient_id);
CREATE INDEX idx_patient_contacts_normalized ON patient_contacts(value_normalized)
    WHERE contact_type IN ('PHONE', 'MOBILE') AND is_current = true AND value_normalized IS NOT NULL;

-- Residential, mailing, work, emergency addresses
CREATE TABLE patient_addresses (
    id UUID PRIMARY KEY,
    patient_id UUID NOT NULL REFERENCES patients(id) ON DELETE CASCADE,
    address_type VARCHAR(50) NOT NULL,
    street_address_line_1 VARCHAR(255) NOT NULL,
    street_address_line_2 VARCHAR(255),
    city VARCHAR(255) NOT NULL,
    postal_code VARCHAR(20),
    state_province VARCHAR(255),
    country VARCHAR(3),
    is_current BOOLEAN DEFAULT true,
    is_primary_for_type BOOLEAN DEFAULT false,
    valid_from DATE,
    valid_until DATE,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT valid_address_type CHECK (
        address_type IN ('RESIDENTIAL', 'MAILING', 'WORK', 'EMERGENCY', 'OTHER')
    )
);

-- Partial unique index: only one primary address per type when current
CREATE UNIQUE INDEX idx_patient_addresses_primary ON patient_addresses(patient_id, address_type)
    WHERE is_current = true AND is_primary_for_type = true;

CREATE INDEX idx_patient_addresses_patient_id ON patient_addresses(patient_id);

-- Emergency contact persons
CREATE TABLE patient_emergency_contacts (
    id UUID PRIMARY KEY,
    patient_id UUID NOT NULL REFERENCES patients(id) ON DELETE CASCADE,
    relationship VARCHAR(50) NOT NULL,
    full_name VARCHAR(255) NOT NULL,
    phone_number VARCHAR(20),
    alternative_phone VARCHAR(20),
    email VARCHAR(255),
    address VARCHAR(510),
    is_primary_contact BOOLEAN DEFAULT false,
    notes TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT valid_relationship CHECK (
        relationship IN ('SPOUSE', 'PARENT', 'CHILD', 'SIBLING', 'RELATIVE',
                         'FRIEND', 'LEGAL_GUARDIAN', 'OTHER')
    )
);

CREATE INDEX idx_patient_emergency_contacts_patient_id ON patient_emergency_contacts(patient_id);
