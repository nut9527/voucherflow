package com.example.voucherflow.redemption.model;

import java.time.Instant;
import com.example.voucherflow.campaign.model.FulfillmentMode;

/** 券持久化与查询模型，关联来源订单、所属用户和供应商券引用。 */
public class VoucherRow {
    private Long id;
    private long orderId;
    private long campaignId;
    private long userId;
    private String campaignName;
    private VoucherStatus status;
    private Instant issuedAt;
    private Instant expiresAt;
    private Instant usedAt;
    private String supplierVoucherRef;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public long getOrderId() { return orderId; }
    public void setOrderId(long orderId) { this.orderId = orderId; }

    public long getCampaignId() { return campaignId; }
    public void setCampaignId(long campaignId) { this.campaignId = campaignId; }

    public long getUserId() { return userId; }
    public void setUserId(long userId) { this.userId = userId; }

    public String getCampaignName() { return campaignName; }
    public void setCampaignName(String campaignName) { this.campaignName = campaignName; }

    public VoucherStatus getStatus() { return status; }
    public void setStatus(VoucherStatus status) { this.status = status; }

    public Instant getIssuedAt() { return issuedAt; }
    public void setIssuedAt(Instant issuedAt) { this.issuedAt = issuedAt; }

    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }

    public Instant getUsedAt() { return usedAt; }
    public void setUsedAt(Instant usedAt) { this.usedAt = usedAt; }

    public String getSupplierVoucherRef() { return supplierVoucherRef; }
    public void setSupplierVoucherRef(String supplierVoucherRef) { this.supplierVoucherRef = supplierVoucherRef; }
}
