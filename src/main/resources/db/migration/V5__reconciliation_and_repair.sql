CREATE TABLE reconciliation_runs (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    campaign_id BIGINT NOT NULL,
    cutoff_at DATETIME(3) NOT NULL,
    state VARCHAR(16) NOT NULL DEFAULT 'READY',
    last_seen_id BIGINT NOT NULL DEFAULT 0,
    scanned_count BIGINT NOT NULL DEFAULT 0,
    lease_token CHAR(36),
    lease_until DATETIME(3),
    started_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    finished_at DATETIME(3),
    CONSTRAINT fk_reconciliation_campaign FOREIGN KEY (campaign_id) REFERENCES campaigns(id),
    CONSTRAINT ck_reconciliation_state CHECK (state IN ('READY','RUNNING','COMPLETED')),
    INDEX ix_reconciliation_due (state,lease_until,id)
) ENGINE=InnoDB;

CREATE TABLE reconciliation_diffs (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    run_id BIGINT NOT NULL,
    order_id BIGINT NOT NULL,
    diff_type VARCHAR(48) NOT NULL,
    observed_revision BIGINT NOT NULL,
    supplier_version BIGINT NOT NULL DEFAULT 0,
    target_event_id CHAR(36),
    target_event_key CHAR(36) GENERATED ALWAYS AS (COALESCE(target_event_id,'')) STORED,
    evidence JSON NOT NULL,
    state VARCHAR(24) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    resolved_at DATETIME(3),
    CONSTRAINT fk_diff_run FOREIGN KEY (run_id) REFERENCES reconciliation_runs(id),
    CONSTRAINT fk_diff_order FOREIGN KEY (order_id) REFERENCES fulfillment_orders(id),
    CONSTRAINT uk_diff_observation UNIQUE(run_id,order_id,diff_type,observed_revision,supplier_version,target_event_key),
    CONSTRAINT ck_diff_state CHECK(state IN ('OBSERVING','OPEN','NEEDS_RECHECK','RESOLVED','REVIEW_ONLY')),
    INDEX ix_diff_run_page (run_id,id)
) ENGINE=InnoDB;

CREATE TABLE repair_commands (
    command_id CHAR(36) NOT NULL PRIMARY KEY,
    diff_id BIGINT NOT NULL,
    order_id BIGINT NOT NULL,
    expected_revision BIGINT NOT NULL,
    command_fingerprint CHAR(64) NOT NULL,
    operation VARCHAR(24) NOT NULL,
    operator_id BIGINT NOT NULL,
    reason VARCHAR(500) NOT NULL,
    result JSON,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_repair_diff FOREIGN KEY (diff_id) REFERENCES reconciliation_diffs(id),
    CONSTRAINT fk_repair_order FOREIGN KEY (order_id) REFERENCES fulfillment_orders(id),
    CONSTRAINT fk_repair_operator FOREIGN KEY (operator_id) REFERENCES users(id),
    CONSTRAINT ck_repair_operation CHECK(operation IN ('CONFIRM','RELEASE','REQUERY','REPLAY_OUTBOX')),
    INDEX ix_repair_order (order_id,created_at)
) ENGINE=InnoDB;
ALTER TABLE stock_ledger ADD CONSTRAINT fk_ledger_repair FOREIGN KEY (repair_command_id) REFERENCES repair_commands(command_id);
