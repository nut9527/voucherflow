CREATE TABLE campaigns (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    description VARCHAR(2000) NOT NULL DEFAULT '',
    total_stock INT NOT NULL,
    available_stock INT NOT NULL,
    reserved_stock INT NOT NULL DEFAULT 0,
    issued_stock INT NOT NULL DEFAULT 0,
    status VARCHAR(16) NOT NULL,
    start_at DATETIME(3) NOT NULL,
    end_at DATETIME(3) NOT NULL,
    redeem_end_at DATETIME(3) NOT NULL,
    fulfillment_mode VARCHAR(16) NOT NULL,
    supplier_id VARCHAR(64),
    product_code VARCHAR(128),
    contract_version VARCHAR(64),
    created_by BIGINT NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_campaign_creator FOREIGN KEY (created_by) REFERENCES users(id),
    CONSTRAINT ck_campaign_stock_nonnegative CHECK (total_stock > 0 AND total_stock <= 1000000000 AND available_stock >= 0 AND reserved_stock >= 0 AND issued_stock >= 0),
    CONSTRAINT ck_campaign_stock_sum CHECK (total_stock = available_stock + reserved_stock + issued_stock),
    CONSTRAINT ck_campaign_window CHECK (start_at < end_at AND end_at <= redeem_end_at),
    CONSTRAINT ck_campaign_status CHECK (status IN ('DRAFT','ACTIVE','DISABLED','ENDED')),
    CONSTRAINT ck_campaign_mode CHECK (
        (fulfillment_mode = 'LOCAL' AND supplier_id IS NULL AND product_code IS NULL AND contract_version IS NULL)
        OR (fulfillment_mode = 'SUPPLIER' AND supplier_id IS NOT NULL AND CHAR_LENGTH(supplier_id) > 0
            AND product_code IS NOT NULL AND CHAR_LENGTH(product_code) > 0
            AND contract_version IS NOT NULL AND CHAR_LENGTH(contract_version) > 0)
    ),
    INDEX ix_campaign_status_id (status,id)
) ENGINE=InnoDB;

CREATE TABLE fulfillment_orders (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    request_no CHAR(36) NOT NULL,
    campaign_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    fulfillment_mode VARCHAR(16) NOT NULL,
    supplier_id VARCHAR(64),
    product_code VARCHAR(128),
    request_fingerprint CHAR(64),
    idempotency_deadline_at DATETIME(3),
    status VARCHAR(16) NOT NULL,
    revision BIGINT NOT NULL DEFAULT 0,
    expires_at DATETIME(3) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    completed_at DATETIME(3),
    CONSTRAINT fk_order_campaign FOREIGN KEY (campaign_id) REFERENCES campaigns(id),
    CONSTRAINT fk_order_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT uk_order_request_no UNIQUE (request_no),
    -- 同一用户在同一活动中只能有一张订单。
    CONSTRAINT uk_order_campaign_user UNIQUE (campaign_id,user_id),
    CONSTRAINT ck_order_status CHECK (status IN ('PENDING','UNKNOWN','FULFILLED','FAILED')),
    CONSTRAINT ck_order_completion CHECK (
        (status IN ('PENDING','UNKNOWN') AND completed_at IS NULL)
        OR (status IN ('FULFILLED','FAILED') AND completed_at IS NOT NULL)
    ),
    CONSTRAINT ck_order_revision CHECK (revision >= 0),
    CONSTRAINT ck_order_mode CHECK (
        (fulfillment_mode = 'LOCAL' AND supplier_id IS NULL AND product_code IS NULL)
        OR (fulfillment_mode = 'SUPPLIER' AND supplier_id IS NOT NULL AND product_code IS NOT NULL
            AND request_fingerprint IS NOT NULL AND idempotency_deadline_at IS NOT NULL)
    ),
    INDEX ix_order_user_id (user_id,id)
) ENGINE=InnoDB;

CREATE TABLE stock_ledger (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    order_id BIGINT NOT NULL,
    campaign_id BIGINT NOT NULL,
    entry_type VARCHAR(16) NOT NULL,
    delta_available INT NOT NULL,
    delta_reserved INT NOT NULL,
    delta_issued INT NOT NULL,
    terminal_order_id BIGINT GENERATED ALWAYS AS (
        CASE WHEN entry_type IN ('CONFIRM','RELEASE') THEN order_id ELSE NULL END
    ) STORED,
    source_receipt_id BIGINT,
    repair_command_id CHAR(36),
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_ledger_order FOREIGN KEY (order_id) REFERENCES fulfillment_orders(id),
    CONSTRAINT fk_ledger_campaign FOREIGN KEY (campaign_id) REFERENCES campaigns(id),
    -- 每个订单的每种库存变化仅记一次。
    CONSTRAINT uk_ledger_order_type UNIQUE (order_id,entry_type),
    -- 同一订单不能同时确认和释放库存。
    CONSTRAINT uk_ledger_one_terminal UNIQUE (terminal_order_id),
    CONSTRAINT ck_ledger_delta CHECK (
        (entry_type='RESERVE' AND delta_available=-1 AND delta_reserved=1 AND delta_issued=0)
        OR (entry_type='CONFIRM' AND delta_available=0 AND delta_reserved=-1 AND delta_issued=1)
        OR (entry_type='RELEASE' AND delta_available=1 AND delta_reserved=-1 AND delta_issued=0)
    ),
    INDEX ix_ledger_campaign_id (campaign_id,id)
) ENGINE=InnoDB;

CREATE TABLE user_vouchers (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    order_id BIGINT NOT NULL,
    campaign_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL,
    issued_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    expires_at DATETIME(3) NOT NULL,
    used_at DATETIME(3),
    supplier_voucher_ref VARCHAR(128),
    CONSTRAINT fk_voucher_order FOREIGN KEY (order_id) REFERENCES fulfillment_orders(id),
    CONSTRAINT fk_voucher_campaign FOREIGN KEY (campaign_id) REFERENCES campaigns(id),
    CONSTRAINT fk_voucher_user FOREIGN KEY (user_id) REFERENCES users(id),
    -- 一单只生成一张券，同活动每位用户最多持有一张券。
    CONSTRAINT uk_voucher_order UNIQUE (order_id),
    CONSTRAINT uk_voucher_campaign_user UNIQUE (campaign_id,user_id),
    CONSTRAINT ck_voucher_usage CHECK (
        (status='UNUSED' AND used_at IS NULL) OR (status='USED' AND used_at IS NOT NULL)
    ),
    INDEX ix_voucher_user_id (user_id,id)
) ENGINE=InnoDB;

CREATE TABLE redemption_records (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    voucher_id BIGINT NOT NULL,
    operator_id BIGINT NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    -- 重复核销只能返回首次回执。
    CONSTRAINT uk_redemption_voucher UNIQUE (voucher_id),
    CONSTRAINT fk_redemption_voucher FOREIGN KEY (voucher_id) REFERENCES user_vouchers(id),
    CONSTRAINT fk_redemption_operator FOREIGN KEY (operator_id) REFERENCES users(id)
) ENGINE=InnoDB;

CREATE TABLE outbox_events (
    id CHAR(36) NOT NULL PRIMARY KEY,
    event_type VARCHAR(64) NOT NULL,
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id VARCHAR(64) NOT NULL,
    payload JSON NOT NULL,
    state VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempt_count INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    lease_token CHAR(36),
    lease_until DATETIME(3),
    last_error VARCHAR(1000),
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    sent_at DATETIME(3),
    -- 一种业务事实只产生一个事件。
    CONSTRAINT uk_outbox_business_event UNIQUE (event_type,aggregate_type,aggregate_id),
    CONSTRAINT ck_outbox_state CHECK (state IN ('PENDING','SENDING','SENT','FAILED')),
    CONSTRAINT ck_outbox_attempts CHECK (attempt_count >= 0),
    INDEX ix_outbox_pending (state,next_attempt_at,id),
    INDEX ix_outbox_lease (state,lease_until,id)
) ENGINE=InnoDB;
