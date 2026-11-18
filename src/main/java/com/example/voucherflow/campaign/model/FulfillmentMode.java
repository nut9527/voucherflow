package com.example.voucherflow.campaign.model;

/** 区分事务内直接发券的本地模式与异步调用供应商的履约模式。 */
public enum FulfillmentMode {
    LOCAL,
    SUPPLIER
}
