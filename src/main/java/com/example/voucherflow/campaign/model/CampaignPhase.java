package com.example.voucherflow.campaign.model;

/** 面向用户展示的领取阶段，由活动状态与当前时间共同决定。 */
public enum CampaignPhase {
    NOT_STARTED,
    CLAIMABLE,
    ENDED,
    DISABLED
}
