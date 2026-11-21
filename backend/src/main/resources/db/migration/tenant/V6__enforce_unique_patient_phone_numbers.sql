-- V6: Enforce unique patient phone numbers per cabinet (Tunisia-first)
-- Business rule: Within each cabinet, one normalized phone number belongs to only one patient
-- Phone numbers are normalized to E.164 format with Tunisia (+216) as default country

-- Step 1: Check for existing duplicate normalized phone numbers among active contacts
DO $$
DECLARE
    duplicate_groups BIGINT;
    duplicate_rows BIGINT;
BEGIN
    -- Match the unique index exactly, including duplicate rows for one patient.
    -- Pre-release diagnostic correction: never emit contact values.
    SELECT COUNT(*), COALESCE(SUM(row_count), 0) INTO duplicate_groups, duplicate_rows
    FROM (
        SELECT COUNT(*) AS row_count
        FROM patient_contacts
        WHERE contact_type IN ('PHONE', 'MOBILE')
          AND is_current = true
          AND value_normalized IS NOT NULL
        GROUP BY value_normalized
        HAVING COUNT(*) > 1
    ) duplicates;

    RAISE NOTICE 'Phone uniqueness preflight: duplicate_groups=%, duplicate_rows=%', duplicate_groups, duplicate_rows;
    IF duplicate_groups > 0 THEN
        RAISE EXCEPTION 'Cannot enforce unique phone constraint: duplicate_groups=%, duplicate_rows=%. Resolve conflicts before applying this migration.', duplicate_groups, duplicate_rows;
    END IF;
END $$;

-- Step 2: Create partial unique index on normalized phone for active PHONE/MOBILE contacts
-- This enforces: one normalized phone number = one patient per cabinet (across entire tenant schema)
-- Cabinet isolation is already enforced by schema-per-tenant architecture
CREATE UNIQUE INDEX idx_patient_contacts_unique_normalized_phone
ON patient_contacts(value_normalized)
WHERE contact_type IN ('PHONE', 'MOBILE')
  AND is_current = true
  AND value_normalized IS NOT NULL;

-- Step 3: Add index comment documenting Tunisia-first normalization expectation
COMMENT ON INDEX idx_patient_contacts_unique_normalized_phone IS
'Enforces unique normalized phone numbers per patient within cabinet. Phone numbers normalized to E.164 format with Tunisia (+216) default country. Application layer responsible for normalization using libphonenumber or equivalent.';
