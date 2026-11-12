package com.example.voucherflow.fulfillment;
/** 供应商查询证据摘要；仅观察时回执编号为零，修复取证时携带持久回执编号。 */
public record ReceiptEvidence(long receiptId, String status, Long resultVersion, String voucherRef) {}
