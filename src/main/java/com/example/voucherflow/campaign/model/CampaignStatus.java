package com.example.voucherflow.campaign.model;

/** 数据库保存的活动生命周期状态，时间窗口另由领取阶段表达。 */
public enum CampaignStatus {
    DRAFT,
    ACTIVE,
    DISABLED,
    ENDED
}
