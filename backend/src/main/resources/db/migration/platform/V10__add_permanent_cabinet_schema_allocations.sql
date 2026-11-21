-- V10 is the sole legacy admission boundary. Run with old cabinet writers stopped.
-- Backfill/constraints take an exclusive cabinets lock; no tenant DDL/data changes.
LOCK TABLE cabinets IN ACCESS EXCLUSIVE MODE;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM cabinets
        WHERE schema_name <> 'cabinet_' || replace(id::text, '-', '')
    ) THEN
        RAISE EXCEPTION 'V10 legacy cabinet mapping preflight failed';
    END IF;
END;
$$;

CREATE SEQUENCE cabinet_schema_number_seq AS BIGINT
    INCREMENT BY 1 MINVALUE 0 START WITH 0 CACHE 1 NO CYCLE OWNED BY NONE;

CREATE TABLE cabinet_schema_allocations (
    reservation_id UUID CONSTRAINT cabinet_schema_allocations_pk PRIMARY KEY,
    cabinet_id UUID NOT NULL,
    allocation_kind VARCHAR(16) NOT NULL,
    schema_number BIGINT,
    schema_name VARCHAR(63) NOT NULL,
    allocated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT cabinet_schema_allocations_cabinet_unique UNIQUE (cabinet_id),
    CONSTRAINT cabinet_schema_allocations_number_unique UNIQUE (schema_number),
    CONSTRAINT cabinet_schema_allocations_name_unique UNIQUE (schema_name),
    CONSTRAINT cabinet_schema_allocations_mapping_unique UNIQUE (cabinet_id, schema_name),
    CONSTRAINT cabinet_schema_allocations_receipt_key UNIQUE (reservation_id, cabinet_id, schema_name),
    CONSTRAINT cabinet_schema_allocations_kind_and_mapping CHECK (
        (allocation_kind = 'LEGACY' AND schema_number IS NULL
            AND schema_name = 'cabinet_' || replace(cabinet_id::text, '-', ''))
        OR (allocation_kind = 'SEQUENTIAL' AND schema_number IS NOT NULL AND schema_number >= 0
            AND schema_name = 'cabinet' || lpad(schema_number::text, greatest(5, length(schema_number::text)), '0'))
    )
);

-- No foreign key back to mutable cabinet rows: evidence survives their deletion.
INSERT INTO cabinet_schema_allocations (reservation_id, cabinet_id, allocation_kind, schema_name, allocated_at)
SELECT gen_random_uuid(), id, 'LEGACY', schema_name, CURRENT_TIMESTAMP FROM cabinets;

CREATE TABLE cabinet_legacy_schema_observations (
    cabinet_id UUID CONSTRAINT cabinet_legacy_schema_observations_pk PRIMARY KEY,
    reservation_id UUID NOT NULL,
    schema_name VARCHAR(63) NOT NULL,
    namespace_oid BIGINT NOT NULL,
    owner_oid BIGINT NOT NULL,
    observed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT cabinet_legacy_schema_observations_allocation_fk
        FOREIGN KEY (reservation_id, cabinet_id, schema_name)
        REFERENCES cabinet_schema_allocations (reservation_id, cabinet_id, schema_name) ON DELETE RESTRICT,
    CONSTRAINT cabinet_legacy_schema_observations_namespace CHECK (namespace_oid BETWEEN 1 AND 4294967295),
    CONSTRAINT cabinet_legacy_schema_observations_owner CHECK (owner_oid BETWEEN 1 AND 4294967295)
);

-- Missing namespaces get no evidence and are not created/adopted by this migration.
INSERT INTO cabinet_legacy_schema_observations (cabinet_id, reservation_id, schema_name, namespace_oid, owner_oid)
SELECT a.cabinet_id, a.reservation_id, a.schema_name, n.oid::bigint, n.nspowner::bigint
FROM cabinet_schema_allocations a JOIN pg_catalog.pg_namespace n ON n.nspname = a.schema_name;

CREATE TABLE cabinet_schema_creation_receipts (
    reservation_id UUID CONSTRAINT cabinet_schema_creation_receipts_pk PRIMARY KEY,
    cabinet_id UUID NOT NULL,
    schema_name VARCHAR(63) NOT NULL,
    namespace_oid BIGINT NOT NULL,
    owner_oid BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT cabinet_schema_creation_receipts_cabinet_unique UNIQUE (cabinet_id),
    CONSTRAINT cabinet_schema_creation_receipts_name_unique UNIQUE (schema_name),
    CONSTRAINT cabinet_schema_creation_receipts_allocation_fk
        FOREIGN KEY (reservation_id, cabinet_id, schema_name)
        REFERENCES cabinet_schema_allocations (reservation_id, cabinet_id, schema_name) ON DELETE RESTRICT,
    CONSTRAINT cabinet_schema_creation_receipts_namespace CHECK (namespace_oid BETWEEN 1 AND 4294967295),
    CONSTRAINT cabinet_schema_creation_receipts_owner CHECK (owner_oid BETWEEN 1 AND 4294967295)
);

ALTER TABLE cabinets ADD CONSTRAINT cabinets_permanent_schema_allocation_fk
    FOREIGN KEY (id, schema_name) REFERENCES cabinet_schema_allocations (cabinet_id, schema_name) ON DELETE RESTRICT;

CREATE FUNCTION "${flyway:defaultSchema}".reject_permanent_evidence_mutation()
RETURNS TRIGGER LANGUAGE plpgsql SET search_path = pg_catalog AS $$
BEGIN
    RAISE EXCEPTION 'Permanent cabinet evidence is immutable' USING ERRCODE = '23000';
END;
$$;

CREATE TRIGGER cabinet_schema_allocations_immutable
    BEFORE UPDATE OR DELETE OR TRUNCATE ON cabinet_schema_allocations
    FOR EACH STATEMENT EXECUTE FUNCTION "${flyway:defaultSchema}".reject_permanent_evidence_mutation();
CREATE TRIGGER cabinet_legacy_schema_observations_closed
    BEFORE INSERT OR UPDATE OR DELETE OR TRUNCATE ON cabinet_legacy_schema_observations
    FOR EACH STATEMENT EXECUTE FUNCTION "${flyway:defaultSchema}".reject_permanent_evidence_mutation();
CREATE TRIGGER cabinet_schema_creation_receipts_immutable
    BEFORE UPDATE OR DELETE OR TRUNCATE ON cabinet_schema_creation_receipts
    FOR EACH STATEMENT EXECUTE FUNCTION "${flyway:defaultSchema}".reject_permanent_evidence_mutation();

CREATE FUNCTION "${flyway:defaultSchema}".require_sequential_allocation()
RETURNS TRIGGER LANGUAGE plpgsql SET search_path = pg_catalog AS $$
BEGIN
    IF NEW.allocation_kind <> 'SEQUENTIAL' THEN
        RAISE EXCEPTION 'Legacy allocation admission is closed' USING ERRCODE = '23000';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER cabinet_schema_allocations_sequential_insert
    BEFORE INSERT ON cabinet_schema_allocations FOR EACH ROW
    EXECUTE FUNCTION "${flyway:defaultSchema}".require_sequential_allocation();

CREATE FUNCTION "${flyway:defaultSchema}".guard_permanent_cabinet_mapping()
RETURNS TRIGGER LANGUAGE plpgsql SET search_path = pg_catalog AS $$
BEGIN
    IF TG_OP = 'UPDATE' THEN
        IF NEW.id IS DISTINCT FROM OLD.id OR NEW.schema_name IS DISTINCT FROM OLD.schema_name THEN
            RAISE EXCEPTION 'Permanent cabinet mapping cannot change' USING ERRCODE = '23000';
        END IF;
    ELSIF NOT EXISTS (
        SELECT 1 FROM "${flyway:defaultSchema}".cabinet_schema_allocations
        WHERE cabinet_id = NEW.id AND schema_name = NEW.schema_name AND allocation_kind = 'SEQUENTIAL'
    ) THEN
        RAISE EXCEPTION 'Cabinet requires its sequential reservation' USING ERRCODE = '23000';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER cabinets_permanent_mapping
    BEFORE INSERT OR UPDATE ON cabinets FOR EACH ROW
    EXECUTE FUNCTION "${flyway:defaultSchema}".guard_permanent_cabinet_mapping();

CREATE FUNCTION "${flyway:defaultSchema}".guard_creation_receipt()
RETURNS TRIGGER LANGUAGE plpgsql SET search_path = pg_catalog AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM "${flyway:defaultSchema}".cabinet_schema_allocations
        WHERE reservation_id = NEW.reservation_id AND cabinet_id = NEW.cabinet_id
            AND schema_name = NEW.schema_name AND allocation_kind = 'SEQUENTIAL'
    ) THEN
        RAISE EXCEPTION 'Creation receipt requires its sequential reservation' USING ERRCODE = '23000';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_catalog.pg_namespace
        WHERE nspname = NEW.schema_name AND oid::bigint = NEW.namespace_oid AND nspowner::bigint = NEW.owner_oid
    ) THEN
        RAISE EXCEPTION 'Creation receipt catalog identity does not match' USING ERRCODE = '23000';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER cabinet_schema_creation_receipts_binding
    BEFORE INSERT ON cabinet_schema_creation_receipts FOR EACH ROW
    EXECUTE FUNCTION "${flyway:defaultSchema}".guard_creation_receipt();
