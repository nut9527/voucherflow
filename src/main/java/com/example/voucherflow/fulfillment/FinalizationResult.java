package com.example.voucherflow.fulfillment;
import com.example.voucherflow.issuance.model.FulfillmentStatus;
/** 返回本地履约确认结果，以及本次调用是否复用了已有结果。 */
public record FinalizationResult(long orderId, FulfillmentStatus status, long voucherId, boolean replayed) {}
