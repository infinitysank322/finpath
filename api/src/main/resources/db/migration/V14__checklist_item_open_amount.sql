-- V14 (T71b, FR-26): an EMI edit mid-cycle updates the amount of the loan's still-open checklist items.
-- amount_paise becomes updatable, but only on an item that is not done and whose cycle is OPEN: a column
-- grant cannot restrict rows, so the trigger below rejects any other amount change (done items and
-- closed cycles stay frozen). No column is added; keys and ownership stay immutable.

CREATE FUNCTION checklist_item_amount_guard() RETURNS trigger
    LANGUAGE plpgsql
    SECURITY INVOKER
    SET search_path = public, pg_temp
AS $$
BEGIN
    IF NEW.amount_paise IS DISTINCT FROM OLD.amount_paise
       AND (OLD.done_at IS NOT NULL
            OR NOT EXISTS (SELECT 1 FROM plan_cycle p WHERE p.id = OLD.plan_cycle_id AND p.status = 'OPEN')) THEN
        RAISE EXCEPTION 'checklist amount is frozen' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER checklist_item_amount_guard BEFORE UPDATE OF amount_paise ON checklist_item
    FOR EACH ROW EXECUTE FUNCTION checklist_item_amount_guard();

GRANT UPDATE (amount_paise) ON checklist_item TO app_user;
