package com.example.voucherflow.outbox;

/** 已领取的待发布事件，携带本次租约令牌和累计尝试次数。 */
public record LeasedEvent(
        String eventId, String eventType, String payload, String leaseToken, int attemptCount) {}
