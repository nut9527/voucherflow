package com.example.voucherflow.reconciliation;

import com.example.voucherflow.common.BusinessException;
import com.example.voucherflow.common.ErrorCode;
import com.example.voucherflow.fulfillment.FulfillmentFinalizer;
import com.example.voucherflow.fulfillment.FulfillmentJobService;
import com.example.voucherflow.fulfillment.ReceiptEvidence;
import com.example.voucherflow.fulfillment.SupplierEvidenceService;
import com.example.voucherflow.identity.CurrentUser;
import com.example.voucherflow.outbox.OutboxRecoveryQuery;
import com.example.voucherflow.outbox.OutboxReplayService;
import com.example.voucherflow.reconciliation.ReconciliationDtos.RepairPreview;
import com.example.voucherflow.reconciliation.ReconciliationDtos.RepairReceipt;
import com.example.voucherflow.reconciliation.ReconciliationDtos.RepairRequest;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** 执行修复预览与幂等修复命令，通过新证据和订单版本约束终态变更。 */
@Service
public class RepairService {
    private final CurrentUser user;
    private final ReconciliationStore store;
    private final CommandFingerprint fingerprints;
    private final SupplierEvidenceService supplier;
    private final FulfillmentFinalizer finalizer;
    private final FulfillmentJobService jobs;
    private final OutboxRecoveryQuery outbox;
    private final OutboxReplayService replay;
    private final DifferenceClassifier classifier;
    private final LocalReconciliationService local;
    private final TransactionTemplate tx;

    public RepairService(
            CurrentUser user,
            ReconciliationStore store,
            CommandFingerprint fingerprints,
            SupplierEvidenceService supplier,
            FulfillmentFinalizer finalizer,
            FulfillmentJobService jobs,
            OutboxRecoveryQuery outbox,
            OutboxReplayService replay,
            DifferenceClassifier classifier,
            LocalReconciliationService local,
            TransactionTemplate tx) {
        this.user = user;
        this.store = store;
        this.fingerprints = fingerprints;
        this.supplier = supplier;
        this.finalizer = finalizer;
        this.jobs = jobs;
        this.outbox = outbox;
        this.replay = replay;
        this.classifier = classifier;
        this.local = local;
        this.tx = tx;
    }

    /** 基于当前订单和远程观察生成可执行动作，预览本身不改变履约状态。 */
    public RepairPreview preview(long id) {
        user.requireAdminId();
        var diff = store.diff(id, false);
        var before = store.order(Long.parseLong(diff.orderId()));
        String remote = "UNAVAILABLE";
        Long version = null;
        List<RepairOperation> allowed = List.of();
        var after = before;
        if (diff.targetEventId() != null) {
            var event = outbox.findByEventId(diff.targetEventId());
            after = store.order(before.id());
            if (event.isPresent()
                    && event.get().orderId() == before.id()
                    && before.revision() == after.revision()
                    && event.get().state().equals("FAILED")
                    && event.get().grantedTotal() < 6)
                allowed = List.of(RepairOperation.REPLAY_OUTBOX);
        } else if (before.mode().equals("SUPPLIER")) {
            var evidence = supplier.observe(before.id());
            remote = evidence.status();
            version = evidence.resultVersion();
            boolean consistent = local.inspect(before.campaignId()).consistent();
            after = store.order(before.id());
            var result = classifier.classify(before, remote, after, consistent);
            if (result != null) allowed = result.operations();
        }
        if (diff.state().equals("RESOLVED") || diff.state().equals("REVIEW_ONLY"))
            allowed = List.of();
        // 返回查单后的订单版本供修复请求携带；预览中的允许动作只是当前观察，执行时还会重新判断。
        return new RepairPreview(
                diff.id(),
                diff.orderId(),
                after.revision(),
                remote,
                version,
                diff.targetEventId(),
                allowed,
                store.now());
    }

    /** 以命令编号幂等执行修复，并在持锁事务中重新核对订单版本。 */
    public RepairReceipt repair(long id, RepairRequest request) {
        long operator = user.requireAdminId();
        var diff = store.diff(id, false);
        long orderId = Long.parseLong(diff.orderId());
        var fingerprint =
                fingerprints.calculate(id, orderId, diff.targetEventId(), operator, request);
        var previous = previous(request.commandId().toString(), fingerprint);
        if (previous.isPresent()) return previous.get();
        ReceiptEvidence evidence = null;
        if (request.operation() == RepairOperation.CONFIRM
                || request.operation() == RepairOperation.RELEASE) {
            if (!local.inspect(store.order(orderId).campaignId()).consistent())
                throw new BusinessException(ErrorCode.REPAIR_NOT_ALLOWED);
            // 确认或释放必须重新向供应商取证，不能直接使用先前预览的结果。
            evidence = supplier.queryForRepair(orderId);
            // 专用回执已独立存为 HELD_FOR_COMMAND，后台扫描不会抢先应用；真正改订单要等下方持锁事务。
            String required =
                    request.operation() == RepairOperation.CONFIRM ? "SUCCEEDED" : "REJECTED";
            if (!required.equals(evidence.status()))
                throw new BusinessException(ErrorCode.REPAIR_NOT_ALLOWED);
        }
        var fresh = evidence;
        try {
            return tx.execute(
                    s -> {
                        // 沿用履约终态处理的活动、订单加锁顺序，再锁差异；网络取证在此前完成，避免持锁等待远端。
                        store.lockCampaign(store.order(orderId).campaignId());
                        var order = store.lockOrder(orderId);
                        var locked = store.diff(id, true);
                        if (!locked.orderId().equals(diff.orderId())
                                || !java.util.Objects.equals(
                                        locked.targetEventId(), diff.targetEventId()))
                            throw new BusinessException(ErrorCode.REPAIR_NOT_ALLOWED);
                        // 等锁期间同一命令可能已经成功，先重放回执；只有新命令才要求订单仍匹配预览版本。
                        var existing = previous(request.commandId().toString(), fingerprint);
                        if (existing.isPresent()) return existing.get();
                        if (order.revision() != request.expectedRevision())
                            throw new BusinessException(ErrorCode.REVISION_CONFLICT);
                        if (locked.state().equals("REVIEW_ONLY")
                                || locked.state().equals("RESOLVED"))
                            throw new BusinessException(ErrorCode.REPAIR_NOT_ALLOWED);
                        // 命令、业务变更和回执共用事务，重复请求可返回首次执行结果。
                        store.insertCommand(id, orderId, operator, fingerprint, request);
                        // 重查或重放消息仅恢复推进机会，仍需复核；只有本次实际落到订单终态才标记 RESOLVED。
                        String state = "NEEDS_RECHECK";
                        switch (request.operation()) {
                            case CONFIRM, RELEASE -> {
                                if (order.terminal() || locked.targetEventId() != null)
                                    throw new BusinessException(ErrorCode.REPAIR_NOT_ALLOWED);
                                var result =
                                        finalizer.applyReceiptInCurrentTransaction(
                                                orderId,
                                                fresh.receiptId(),
                                                request.commandId().toString(),
                                                request.expectedRevision());
                                if (!store.order(orderId).terminal())
                                    throw new BusinessException(ErrorCode.REPAIR_NOT_ALLOWED);
                                state = "RESOLVED";
                            }
                            case REQUERY -> {
                                if (locked.targetEventId() != null
                                        || order.terminal()
                                        || !order.mode().equals("SUPPLIER")
                                        || !order.jobState().equals("PAUSED"))
                                    throw new BusinessException(ErrorCode.REPAIR_NOT_ALLOWED);
                                jobs.requeryInCurrentTransaction(orderId);
                            }
                            case REPLAY_OUTBOX -> {
                                if (locked.targetEventId() == null)
                                    throw new BusinessException(ErrorCode.REPAIR_NOT_ALLOWED);
                                replay.replayInCurrentTransaction(
                                        orderId,
                                        locked.targetEventId(),
                                        request.commandId().toString());
                            }
                        }
                        var receipt =
                                new RepairReceipt(
                                        request.commandId().toString(),
                                        Long.toString(id),
                                        Long.toString(orderId),
                                        request.operation(),
                                        store.order(orderId).revision(),
                                        state);
                        store.completeCommand(receipt);
                        return receipt;
                    });
        } catch (DuplicateKeyException ex) {
            return previous(request.commandId().toString(), fingerprint).orElseThrow(() -> ex);
        }
    }

    /** 命令编号相同且内容指纹一致时复用回执，否则报告命令冲突。 */
    private Optional<RepairReceipt> previous(String id, String fingerprint) {
        return store.command(id)
                .map(
                        command -> {
                            if (!command.fingerprint().equals(fingerprint))
                                throw new BusinessException(ErrorCode.COMMAND_CONFLICT);
                            return command.receipt();
                        });
    }
}
