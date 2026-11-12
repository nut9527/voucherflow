ALTER TABLE reconciliation_runs
    ADD accounting_valid BOOLEAN,
    ADD accounting_observed_at DATETIME(3);

ALTER TABLE outbox_events
    ADD order_id BIGINT GENERATED ALWAYS AS (
        CAST(JSON_UNQUOTE(JSON_EXTRACT(payload,'$.data.orderId')) AS UNSIGNED)
    ) STORED,
    ADD INDEX ix_outbox_order_state (order_id,state,id);
