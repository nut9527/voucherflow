package com.example.voucherflow.issuance.model;
/** 表示履约进度；结果未知仍需查证，只有发放成功和明确失败属于终态。 */
public enum FulfillmentStatus {
    PENDING, UNKNOWN, FULFILLED, FAILED;
    public boolean isTerminal() { return this == FULFILLED || this == FAILED; }
}
