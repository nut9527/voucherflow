package com.example.voucherflow.reconciliation;

import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 对账任务、差异及修复命令的接口请求与响应结构。 */
public final class ReconciliationDtos {
    private ReconciliationDtos() {}

    public record RunRequest(@Positive long campaignId) {}

    public record RunDetail(
            String id,
            String campaignId,
            String state,
            String lastSeenId,
            String upperOrderId,
            long scannedCount,
            Instant cutoffAt,
            Instant startedAt,
            Instant finishedAt) {}

    public record DiffDetail(
            String id,
            String runId,
            String orderId,
            String diffType,
            long observedRevision,
            long supplierVersion,
            String targetEventId,
            String state,
            String evidence,
            Instant createdAt) {}

    public record RepairRequest(
            @NotNull UUID commandId,
            @PositiveOrZero long expectedRevision,
            @NotNull RepairOperation operation,
            @NotBlank @Size(max = 500) String reason) {}

    public record RepairPreview(
            String diffId,
            String orderId,
            long currentRevision,
            String supplierStatus,
            Long supplierVersion,
            String targetEventId,
            List<RepairOperation> allowedOperations,
            Instant observedAt) {}

    public record RepairReceipt(
            String commandId,
            String diffId,
            String orderId,
            RepairOperation operation,
            long revision,
            String diffState) {}
}
