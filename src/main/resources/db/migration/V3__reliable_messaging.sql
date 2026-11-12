ALTER TABLE outbox_events
    ADD COLUMN manual_publish_credits INT NOT NULL DEFAULT 0,
    ADD COLUMN manual_publish_granted_total INT NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_outbox_manual_budget CHECK (
        manual_publish_credits >= 0 AND manual_publish_granted_total BETWEEN 0 AND 6
        AND manual_publish_credits <= manual_publish_granted_total);

CREATE TABLE consumed_events (
    consumer_name VARCHAR(64) NOT NULL,
    event_id CHAR(36) NOT NULL,
    payload_hash CHAR(64) NOT NULL,
    consumed_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (consumer_name,event_id)
) ENGINE=InnoDB;

CREATE TABLE notifications (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    event_id CHAR(36) NOT NULL,
    user_id BIGINT NOT NULL,
    order_id BIGINT NOT NULL,
    voucher_id BIGINT,
    channel VARCHAR(16) NOT NULL DEFAULT 'IN_APP',
    content VARCHAR(256) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT uk_notification_event_user_channel UNIQUE (event_id,user_id,channel),
    CONSTRAINT fk_notification_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT fk_notification_order FOREIGN KEY (order_id) REFERENCES fulfillment_orders(id),
    CONSTRAINT fk_notification_voucher FOREIGN KEY (voucher_id) REFERENCES user_vouchers(id),
    INDEX ix_notification_user_id (user_id,id)
) ENGINE=InnoDB;
