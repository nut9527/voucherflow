ALTER TABLE supplier_receipts
    ADD next_apply_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    DROP INDEX ix_receipt_pending,
    ADD INDEX ix_receipt_pending (apply_state, next_apply_at, id);
