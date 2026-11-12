package com.example.voucherflow.fulfillment;
/** 从订单与券表核实后的通知归属信息。 */
public record NotificationFacts(long userId, long orderId, Long voucherId) {}
