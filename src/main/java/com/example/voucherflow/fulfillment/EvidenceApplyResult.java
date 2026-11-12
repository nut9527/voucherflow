package com.example.voucherflow.fulfillment;
/** 记录供应商回执的处理结论及处理后的订单版本。 */
public record EvidenceApplyResult(String disposition, long revision) {}
