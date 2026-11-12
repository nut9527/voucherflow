CREATE INDEX ix_outbox_state_created ON outbox_events(state,created_at);
CREATE INDEX ix_order_status_created ON fulfillment_orders(status,created_at);
CREATE INDEX ix_diff_state ON reconciliation_diffs(state);
