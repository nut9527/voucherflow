ALTER TABLE fulfillment_orders
 ADD contract_version VARCHAR(64),
 ADD subject_ref VARCHAR(128),
 ADD job_state VARCHAR(24) NOT NULL DEFAULT 'DONE',
 ADD next_action VARCHAR(8) NOT NULL DEFAULT 'ISSUE',
 ADD next_run_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 ADD execution_token CHAR(36),
 ADD lease_until DATETIME(3),
 ADD attempt_count INT NOT NULL DEFAULT 0,
 ADD issue_attempts INT NOT NULL DEFAULT 0,
 ADD query_attempts INT NOT NULL DEFAULT 0,
 ADD manual_execution BOOLEAN NOT NULL DEFAULT FALSE,
 ADD manual_query_credits INT NOT NULL DEFAULT 0,
 ADD manual_query_granted_total INT NOT NULL DEFAULT 0,
 ADD last_result_version BIGINT,
 ADD last_accepted_receipt_id BIGINT,
 ADD late_fulfillment BOOLEAN NOT NULL DEFAULT FALSE,
 ADD CONSTRAINT ck_job_state CHECK (job_state IN ('WAITING_EVENT','READY','RUNNING','PAUSED','DONE')),
 ADD CONSTRAINT ck_manual_query_budget CHECK (manual_query_credits BETWEEN 0 AND 3 AND manual_query_granted_total BETWEEN 0 AND 3 AND manual_query_credits<=manual_query_granted_total),
 ADD INDEX ix_job_ready(job_state,next_run_at,id),
 ADD INDEX ix_job_lease(job_state,lease_until,id);

CREATE TABLE fulfillment_attempts (
 id CHAR(36) PRIMARY KEY,
 order_id BIGINT NOT NULL,
 execution_token CHAR(36) NOT NULL,
 action VARCHAR(8) NOT NULL,
 outcome VARCHAR(32),
 started_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 finished_at DATETIME(3),
 FOREIGN KEY(order_id) REFERENCES fulfillment_orders(id),
 INDEX ix_attempt_order(order_id,started_at)
) ENGINE=InnoDB;


CREATE TABLE supplier_receipts (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 order_id BIGINT NOT NULL,
 supplier_id VARCHAR(64) NOT NULL,
 receipt_key VARCHAR(128) NOT NULL,
 payload_hash CHAR(64) NOT NULL,
 source VARCHAR(24) NOT NULL,
 request_no CHAR(36) NOT NULL,
 product_code VARCHAR(128) NOT NULL,
 subject_ref VARCHAR(128) NOT NULL,
 status VARCHAR(16) NOT NULL,
 result_version BIGINT,
 voucher_ref VARCHAR(128),
 execution_token CHAR(36),
 apply_state VARCHAR(24) NOT NULL,
 observed_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 applied_at DATETIME(3),
 FOREIGN KEY(order_id) REFERENCES fulfillment_orders(id),
 UNIQUE KEY uk_supplier_receipt(supplier_id,receipt_key),
 INDEX ix_receipt_pending(apply_state,id),
 INDEX ix_receipt_order(order_id,id),
 CONSTRAINT ck_receipt_result CHECK (
   (status='NOT_FOUND' AND result_version IS NULL AND voucher_ref IS NULL)
   OR (status IN ('ACCEPTED','REJECTED') AND result_version > 0 AND voucher_ref IS NULL)
   OR (status='SUCCEEDED' AND result_version > 0 AND voucher_ref IS NOT NULL)),
 CONSTRAINT ck_receipt_apply CHECK (apply_state IN ('PENDING','APPLIED','HELD_FOR_COMMAND','IGNORED','CONFLICT','STALE'))
) ENGINE=InnoDB;

ALTER TABLE stock_ledger ADD CONSTRAINT fk_ledger_supplier_receipt
 FOREIGN KEY(source_receipt_id) REFERENCES supplier_receipts(id);
