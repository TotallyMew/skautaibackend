-- Frozen mobile audit scopes. Legacy sessions remain readable and can be cancelled.
ALTER TABLE item_check_sessions
    ADD COLUMN snapshot_json TEXT,
    ADD COLUMN revision INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN last_mutation_id UUID,
    ADD COLUMN title VARCHAR(160),
    ADD COLUMN scope_location_id UUID REFERENCES locations(id) ON DELETE SET NULL;
ALTER TABLE item_check_sessions DROP CONSTRAINT item_check_sessions_status_check;
ALTER TABLE item_check_sessions ADD CONSTRAINT item_check_sessions_status_check
    CHECK (status IN ('OPEN', 'COMPLETED', 'CANCELLED'));
ALTER TABLE item_checks DROP CONSTRAINT item_checks_quantity_check;
ALTER TABLE item_checks ADD CONSTRAINT item_checks_quantity_check CHECK (quantity >= 0);
ALTER TABLE items ADD COLUMN audit_version BIGINT NOT NULL DEFAULT 0;

-- Changes made through either backend invalidate an in-progress physical count.
CREATE FUNCTION bump_item_audit_version() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    NEW.audit_version := OLD.audit_version + 1;
    RETURN NEW;
END;
$$;
CREATE TRIGGER item_audit_version BEFORE UPDATE OF quantity, condition, status,
    custodian_id, origin, location_id, temporary_storage_label, responsible_user_id
    ON items FOR EACH ROW EXECUTE FUNCTION bump_item_audit_version();

CREATE FUNCTION touch_audit_item_from_movement() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE old_item UUID; new_item UUID;
BEGIN
    IF TG_OP <> 'INSERT' THEN old_item := OLD.item_id; END IF;
    IF TG_OP <> 'DELETE' THEN new_item := NEW.item_id; END IF;
    UPDATE items SET audit_version = audit_version + 1
        WHERE id IN (old_item, new_item);
    RETURN NULL;
END;
$$;
CREATE TRIGGER reservation_audit_version AFTER INSERT OR UPDATE OR DELETE
    ON reservation_movements FOR EACH ROW EXECUTE FUNCTION touch_audit_item_from_movement();
CREATE TRIGGER loan_audit_version AFTER INSERT OR UPDATE OR DELETE
    ON direct_item_loans FOR EACH ROW EXECUTE FUNCTION touch_audit_item_from_movement();

-- Older clients must not modify frozen sessions without the new locking/revision protocol.
CREATE FUNCTION guard_frozen_audit_write() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE audit_id UUID; frozen BOOLEAN;
BEGIN
    IF TG_TABLE_NAME = 'item_checks' THEN
        IF TG_OP = 'DELETE' THEN audit_id := OLD.session_id; ELSE audit_id := NEW.session_id; END IF;
        SELECT snapshot_json IS NOT NULL INTO frozen FROM item_check_sessions WHERE id = audit_id;
    ELSE
        audit_id := OLD.id;
        frozen := OLD.snapshot_json IS NOT NULL;
    END IF;
    IF frozen AND current_setting('skautai.audit_session', true) IS DISTINCT FROM audit_id::text THEN
        RAISE EXCEPTION 'Frozen audit requires the current audit workflow';
    END IF;
    IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER frozen_audit_session_guard BEFORE UPDATE OF status, snapshot_json, revision, last_mutation_id, completed_by_user_id, completed_at, scope_item_count, notes ON item_check_sessions
    FOR EACH ROW EXECUTE FUNCTION guard_frozen_audit_write();
CREATE TRIGGER frozen_audit_check_guard BEFORE INSERT OR UPDATE OF result, quantity, expected_quantity, actual_quantity, actual_location_note, condition_at_check, notes, checked_by_user_id, checked_at OR DELETE ON item_checks
    FOR EACH ROW EXECUTE FUNCTION guard_frozen_audit_write();


-- Non-reservation event custody also leaves physical storage.
CREATE FUNCTION touch_audit_item_from_custody() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE old_event_item UUID; new_event_item UUID;
BEGIN
    IF TG_OP <> 'INSERT' THEN old_event_item := OLD.event_inventory_item_id; END IF;
    IF TG_OP <> 'DELETE' THEN new_event_item := NEW.event_inventory_item_id; END IF;
    UPDATE items SET audit_version = audit_version + 1 WHERE id IN (
        SELECT item_id FROM event_inventory_items WHERE id IN (old_event_item, new_event_item)
    );
    RETURN NULL;
END;
$$;
CREATE TRIGGER custody_audit_version AFTER INSERT OR UPDATE OR DELETE ON event_inventory_custody
    FOR EACH ROW EXECUTE FUNCTION touch_audit_item_from_custody();
CREATE TRIGGER event_item_audit_version AFTER UPDATE OF item_id, reservation_group_id OR DELETE
    ON event_inventory_items FOR EACH ROW EXECUTE FUNCTION touch_audit_item_from_movement();
