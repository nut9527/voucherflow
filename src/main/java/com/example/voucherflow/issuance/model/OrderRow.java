package com.example.voucherflow.issuance.model;

import com.example.voucherflow.campaign.model.FulfillmentMode;
import java.time.Instant;

/** 订单持久化模型，保存领取身份、履约方式及供应商幂等请求快照。 */
public class OrderRow {
    private Long id;
    // 请求号随订单保存，供应商重试与查单始终复用同一个幂等键。
    private String requestNo;
    private long campaignId;
    private long userId;
    private FulfillmentMode fulfillmentMode;
    private String supplierId;
    private String productCode;
    // 指纹绑定商品与用户主体，供供应商识别同键不同请求内容的冲突。
    private String requestFingerprint;
    private Instant idempotencyDeadlineAt;
    private FulfillmentStatus status;
    private Instant expiresAt;
    private Instant createdAt;
    private String contractVersion;
    private String subjectRef;

    public String getContractVersion() {
        return contractVersion;
    }

    public void setContractVersion(String value) {
        contractVersion = value;
    }

    public String getSubjectRef() {
        return subjectRef;
    }

    public void setSubjectRef(String value) {
        subjectRef = value;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getRequestNo() {
        return requestNo;
    }

    public void setRequestNo(String requestNo) {
        this.requestNo = requestNo;
    }

    public long getCampaignId() {
        return campaignId;
    }

    public void setCampaignId(long campaignId) {
        this.campaignId = campaignId;
    }

    public long getUserId() {
        return userId;
    }

    public void setUserId(long userId) {
        this.userId = userId;
    }

    public FulfillmentMode getFulfillmentMode() {
        return fulfillmentMode;
    }

    public void setFulfillmentMode(FulfillmentMode fulfillmentMode) {
        this.fulfillmentMode = fulfillmentMode;
    }

    public String getSupplierId() {
        return supplierId;
    }

    public void setSupplierId(String supplierId) {
        this.supplierId = supplierId;
    }

    public String getProductCode() {
        return productCode;
    }

    public void setProductCode(String productCode) {
        this.productCode = productCode;
    }

    public String getRequestFingerprint() {
        return requestFingerprint;
    }

    public void setRequestFingerprint(String requestFingerprint) {
        this.requestFingerprint = requestFingerprint;
    }

    public Instant getIdempotencyDeadlineAt() {
        return idempotencyDeadlineAt;
    }

    public void setIdempotencyDeadlineAt(Instant idempotencyDeadlineAt) {
        this.idempotencyDeadlineAt = idempotencyDeadlineAt;
    }

    public FulfillmentStatus getStatus() {
        return status;
    }

    public void setStatus(FulfillmentStatus status) {
        this.status = status;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
