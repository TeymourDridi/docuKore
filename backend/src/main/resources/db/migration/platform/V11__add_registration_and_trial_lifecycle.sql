-- Lock identity writers while auditing/re-keying email; never choose a collision winner.
LOCK TABLE users IN ACCESS EXCLUSIVE MODE;
DO $$
BEGIN
    IF EXISTS (
        SELECT lower(btrim(email, E' \t\n\r\f\v'))
        FROM users WHERE deployment_mode = 'hosted' AND email IS NOT NULL AND btrim(email, E' \t\n\r\f\v') <> ''
        GROUP BY lower(btrim(email, E' \t\n\r\f\v')) HAVING count(*) > 1
    ) THEN
        RAISE EXCEPTION 'V11 canonical email collision preflight failed';
    END IF;
END;
$$;
-- Clear old keys first so a valid swap cannot violate the existing unique index mid-update.
UPDATE users SET email_normalized = NULL;
UPDATE users SET email_normalized = lower(btrim(email, E' \t\n\r\f\v'))
WHERE deployment_mode = 'hosted' AND email IS NOT NULL AND btrim(email, E' \t\n\r\f\v') <> '';
ALTER TABLE users ADD COLUMN email_verified_at TIMESTAMPTZ;

ALTER TABLE cabinets
    ADD COLUMN administrative_status VARCHAR(16) NOT NULL DEFAULT 'AVAILABLE',
    ADD COLUMN provisioning_attempt_id UUID,
    ADD CONSTRAINT cabinets_administrative_status CHECK (administrative_status IN ('AVAILABLE', 'SUSPENDED'));

CREATE TABLE initial_cabinet_registrations (
    id UUID CONSTRAINT initial_cabinet_registrations_pk PRIMARY KEY,
    account_id UUID NOT NULL,
    idempotency_key UUID NOT NULL,
    cabinet_name VARCHAR(255) NOT NULL,
    cabinet_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT initial_cabinet_registrations_account_fk FOREIGN KEY (account_id) REFERENCES users(id) ON DELETE RESTRICT,
    CONSTRAINT initial_cabinet_registrations_account_unique UNIQUE (account_id),
    CONSTRAINT initial_cabinet_registrations_request_unique UNIQUE (idempotency_key),
    CONSTRAINT initial_cabinet_registrations_cabinet_unique UNIQUE (cabinet_id),
    -- The permanent link survives cabinet deletion; it can never authorize another allocation.
    CONSTRAINT initial_cabinet_registrations_allocation_fk FOREIGN KEY (cabinet_id)
        REFERENCES cabinet_schema_allocations(cabinet_id) ON DELETE RESTRICT,
    CONSTRAINT initial_cabinet_registrations_name_not_blank CHECK (btrim(cabinet_name) <> '')
);

CREATE FUNCTION "${flyway:defaultSchema}".guard_initial_registration()
RETURNS TRIGGER LANGUAGE plpgsql SET search_path = pg_catalog AS $$
BEGIN
    IF TG_OP = 'UPDATE' THEN
        IF NEW.id IS DISTINCT FROM OLD.id OR NEW.account_id IS DISTINCT FROM OLD.account_id
            OR NEW.idempotency_key IS DISTINCT FROM OLD.idempotency_key
            OR NEW.created_at IS DISTINCT FROM OLD.created_at
            OR (OLD.cabinet_id IS NOT NULL AND
                (NEW.cabinet_id IS DISTINCT FROM OLD.cabinet_id OR NEW.cabinet_name IS DISTINCT FROM OLD.cabinet_name)) THEN
            RAISE EXCEPTION 'Original registration binding is immutable' USING ERRCODE = '23000';
        END IF;
    END IF;
    IF NEW.cabinet_id IS NOT NULL AND NOT EXISTS (
        SELECT 1 FROM "${flyway:defaultSchema}".cabinets c
        JOIN "${flyway:defaultSchema}".cabinet_schema_allocations a ON a.cabinet_id = c.id AND a.schema_name = c.schema_name
        WHERE c.id = NEW.cabinet_id AND c.owner_id = NEW.account_id AND a.allocation_kind = 'SEQUENTIAL'
    ) THEN
        RAISE EXCEPTION 'Initial cabinet must belong to original account and allocation' USING ERRCODE = '23000';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER initial_cabinet_registrations_binding BEFORE INSERT OR UPDATE ON initial_cabinet_registrations
    FOR EACH ROW EXECUTE FUNCTION "${flyway:defaultSchema}".guard_initial_registration();
CREATE TRIGGER initial_cabinet_registrations_durable BEFORE DELETE OR TRUNCATE ON initial_cabinet_registrations
    FOR EACH STATEMENT EXECUTE FUNCTION "${flyway:defaultSchema}".reject_permanent_evidence_mutation();

CREATE FUNCTION "${flyway:defaultSchema}".guard_initial_cabinet_owner()
RETURNS TRIGGER LANGUAGE plpgsql SET search_path = pg_catalog AS $$
BEGIN
    IF NEW.owner_id IS DISTINCT FROM OLD.owner_id AND EXISTS (
        SELECT 1 FROM "${flyway:defaultSchema}".initial_cabinet_registrations WHERE cabinet_id = OLD.id
    ) THEN
        RAISE EXCEPTION 'Original registration cabinet owner cannot change' USING ERRCODE = '23000';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER cabinets_initial_owner BEFORE UPDATE ON cabinets
    FOR EACH ROW EXECUTE FUNCTION "${flyway:defaultSchema}".guard_initial_cabinet_owner();

CREATE TABLE email_verification_tokens (
    id UUID CONSTRAINT email_verification_tokens_pk PRIMARY KEY,
    registration_id UUID NOT NULL,
    token_hash VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    delivery_attempts INTEGER NOT NULL DEFAULT 0,
    last_delivery_at TIMESTAMPTZ,
    last_delivery_error VARCHAR(64),
    CONSTRAINT email_verification_tokens_registration_fk FOREIGN KEY (registration_id)
        REFERENCES initial_cabinet_registrations(id) ON DELETE RESTRICT,
    CONSTRAINT email_verification_tokens_hash_unique UNIQUE (token_hash),
    CONSTRAINT email_verification_tokens_hash_shape CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT email_verification_tokens_expiry CHECK (expires_at > created_at),
    CONSTRAINT email_verification_tokens_consumption CHECK (consumed_at IS NULL OR consumed_at >= created_at),
    CONSTRAINT email_verification_tokens_attempts CHECK (delivery_attempts >= 0),
    CONSTRAINT email_verification_tokens_error_code CHECK (last_delivery_error IS NULL OR last_delivery_error ~ '^[A-Z][A-Z0-9_]{0,63}$')
);
CREATE INDEX idx_email_verification_tokens_registration ON email_verification_tokens(registration_id, created_at DESC);

CREATE TABLE cabinet_subscriptions (
    id UUID CONSTRAINT cabinet_subscriptions_pk PRIMARY KEY,
    cabinet_id UUID NOT NULL,
    subscription_status VARCHAR(16) NOT NULL,
    trial_started_at TIMESTAMPTZ NOT NULL,
    trial_ends_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT cabinet_subscriptions_initial_trial_unique UNIQUE (cabinet_id),
    CONSTRAINT cabinet_subscriptions_allocation_fk FOREIGN KEY (cabinet_id)
        REFERENCES cabinet_schema_allocations(cabinet_id) ON DELETE RESTRICT,
    CONSTRAINT cabinet_subscriptions_status CHECK (subscription_status IN ('TRIAL', 'ACTIVE', 'EXPIRED', 'CANCELLED')),
    CONSTRAINT cabinet_subscriptions_trial_duration CHECK (trial_ends_at - trial_started_at = interval '720 hours')
);

CREATE FUNCTION "${flyway:defaultSchema}".guard_initial_trial()
RETURNS TRIGGER LANGUAGE plpgsql SET search_path = pg_catalog AS $$
BEGIN
    IF NEW.id IS DISTINCT FROM OLD.id OR NEW.cabinet_id IS DISTINCT FROM OLD.cabinet_id
        OR NEW.trial_started_at IS DISTINCT FROM OLD.trial_started_at OR NEW.trial_ends_at IS DISTINCT FROM OLD.trial_ends_at THEN
        RAISE EXCEPTION 'Initial trial cannot be restarted or reassigned' USING ERRCODE = '23000';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER cabinet_subscriptions_initial_trial BEFORE UPDATE ON cabinet_subscriptions
    FOR EACH ROW EXECUTE FUNCTION "${flyway:defaultSchema}".guard_initial_trial();
CREATE TRIGGER cabinet_subscriptions_durable BEFORE DELETE OR TRUNCATE ON cabinet_subscriptions
    FOR EACH STATEMENT EXECUTE FUNCTION "${flyway:defaultSchema}".reject_permanent_evidence_mutation();
