package com.example.voucherflow.outbox;
/** 对账与修复使用的事件发布状态及人工重试额度视图。 */
public record EventRecoveryView(String eventId, long orderId, String eventType, String state,
        int credits, int grantedTotal) {}
