package com.example.voucherflow.reconciliation;

import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Component;

/** 根据查单前后版本、本地账目和供应商状态判定差异及允许的修复操作。 */
@Component
public class DifferenceClassifier {
    public record Observation(
            long id,
            long campaignId,
            long revision,
            String status,
            String mode,
            String jobState,
            int manualQueryGrantedTotal,
            Instant expiresAt,
            Instant completedAt) {
        public boolean terminal() {
            return status.equals("FULFILLED") || status.equals("FAILED");
        }
    }

    public record Classification(String type, String state, List<RepairOperation> operations) {}

    /** 返回差异与允许动作；结果为空表示本次观察未发现差异。 */
    public Classification classify(
            Observation before, String remote, Observation after, boolean accountingValid) {
        // 远程查单期间本地订单可能变化，此时先复查，不依据混合时点的状态执行修复。
        if (before.revision() != after.revision())
            return result("AWAITING_CONFIRMATION", "NEEDS_RECHECK");
        // 账目不平或终态证据矛盾时标记 REVIEW_ONLY，不授予单笔确认、释放等修复动作。
        if (!accountingValid) return result("LOCAL_ACCOUNTING_BROKEN", "REVIEW_ONLY");
        if (remote.equals("CONFLICT")) return result("TERMINAL_CONFLICT", "REVIEW_ONLY");
        if ((remote.equals("SUCCEEDED") && after.status().equals("FAILED"))
                || (remote.equals("REJECTED") && after.status().equals("FULFILLED"))) {
            return result("TERMINAL_CONFLICT", "REVIEW_ONLY");
        }
        // 只有明确成功或拒绝才能改变库存；未查到、处理中和查单不可用都不是失败凭据。
        if (!after.terminal() && remote.equals("SUCCEEDED"))
            return result("REMOTE_SUCCESS_LOCAL_OPEN", "OPEN", RepairOperation.CONFIRM);
        if (!after.terminal() && remote.equals("REJECTED"))
            return result("REMOTE_REJECT_LOCAL_RESERVED", "OPEN", RepairOperation.RELEASE);
        // 已发放但完成时间越过核销截止的订单只供人工审查，不通过对账撤销既有终态。
        if (after.status().equals("FULFILLED")
                && after.completedAt() != null
                && !after.completedAt().isBefore(after.expiresAt()))
            return result("LATE_FULFILLMENT", "REVIEW_ONLY");
        // 本地虽已结束，远端尚不能确认时仍保留 NEEDS_RECHECK，不能直接视为两边一致。
        if (after.terminal()
                && after.mode().equals("SUPPLIER")
                && !remote.equals("SUCCEEDED")
                && !remote.equals("REJECTED"))
            return result("AWAITING_CONFIRMATION", "NEEDS_RECHECK");
        if (after.terminal()) return null;
        // OPEN 表示当前存在可执行动作；这里仅补一次查单机会。其余未决订单保持 OBSERVING。
        if (after.jobState().equals("PAUSED") && after.manualQueryGrantedTotal() < 3)
            return result("AWAITING_CONFIRMATION", "OPEN", RepairOperation.REQUERY);
        return result("AWAITING_CONFIRMATION", "OBSERVING");
    }

    private Classification result(String type, String state, RepairOperation... operations) {
        return new Classification(type, state, List.of(operations));
    }
}
