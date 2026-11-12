CREATE TABLE supplier_requests (
 request_no CHAR(36) PRIMARY KEY,
 fingerprint CHAR(64) NOT NULL,
 product_code VARCHAR(128) NOT NULL,
 subject_ref VARCHAR(128) NOT NULL,
 status VARCHAR(16) NOT NULL,
 result_version BIGINT NOT NULL,
 idempotency_deadline_at DATETIME(3) NOT NULL,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 CONSTRAINT ck_supplier_status CHECK(status IN ('ACCEPTED','SUCCEEDED','REJECTED')),
 INDEX ix_supplier_progress(status,created_at)
) ENGINE=InnoDB;
CREATE TABLE supplier_vouchers (
 voucher_ref CHAR(36) PRIMARY KEY,
 request_no CHAR(36) NOT NULL UNIQUE,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 FOREIGN KEY(request_no) REFERENCES supplier_requests(request_no)
) ENGINE=InnoDB;
CREATE TABLE callback_jobs (
 event_id CHAR(36) PRIMARY KEY,
 request_no CHAR(36) NOT NULL,
 result_version BIGINT NOT NULL,
 payload JSON NOT NULL,
 state VARCHAR(16) NOT NULL DEFAULT 'PENDING',
 attempt_count INT NOT NULL DEFAULT 0,
 next_send_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 lease_token CHAR(36),
 lease_until DATETIME(3),
 FOREIGN KEY(request_no) REFERENCES supplier_requests(request_no),
 UNIQUE KEY uk_callback_result(request_no,result_version),
 INDEX ix_callback_due(state,next_send_at,event_id),
 INDEX ix_callback_expired(state,lease_until,event_id)
) ENGINE=InnoDB;
