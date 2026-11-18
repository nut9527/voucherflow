package com.example.voucherflow.reconciliation;

/** 支持的修复动作：确认发放、释放预留、重新查单和重放发布事件。 */
public enum RepairOperation {
    CONFIRM,
    RELEASE,
    REQUERY,
    REPLAY_OUTBOX
}
