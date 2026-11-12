package com.example.voucherflow.redemption.model;

import java.time.Instant;
import com.example.voucherflow.campaign.model.FulfillmentMode;

/** 核销回执持久化模型，记录券、操作管理员及首次核销时间。 */
public class RedemptionRow {
    private Long id;
    private long voucherId;
    private long operatorId;
    private Instant createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public long getVoucherId() { return voucherId; }
    public void setVoucherId(long voucherId) { this.voucherId = voucherId; }

    public long getOperatorId() { return operatorId; }
    public void setOperatorId(long operatorId) { this.operatorId = operatorId; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
