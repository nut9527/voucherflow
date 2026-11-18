package com.example.voucherflow.fulfillment;

import com.example.voucherflow.common.BusinessException;
import com.example.voucherflow.common.ErrorCode;
import com.example.voucherflow.outbox.DomainEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FulfillmentJobService {
    private final SupplierStore store;
    private final FulfillmentJobStore jobs;
    private final FulfillmentProperties properties;

    public record Execution(
            SupplierStore.Order order,
            String attemptId,
            String token,
            String action,
            boolean manual) {}

    public FulfillmentJobService(
            SupplierStore store, FulfillmentJobStore jobs, FulfillmentProperties properties) {
        this.store = store;
        this.jobs = jobs;
        this.properties = properties;
    }

    /** 消费去重与任务就绪状态共用事务，消息确认后任务仍可由执行器继续领取。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void registerRequested(DomainEvent event) {
        if (!"FulfillmentRequested.v1".equals(event.eventType())
                || !"FULFILLMENT_ORDER".equals(event.aggregateType()))
            throw new IllegalArgumentException("Unexpected fulfillment event");
        long id = Long.parseLong(event.aggregateId());
        var order = store.lock(id);
        if (order.supplierId() == null
                || !Long.toString(id).equals(event.data().get("orderId"))
                || !Long.toString(order.userId()).equals(event.data().get("userId"))
                || !Long.toString(order.campaignId()).equals(event.data().get("campaignId")))
            throw new IllegalArgumentException("Invalid fulfillment event reference");
        jobs.register(id);
    }

    @Transactional
    public List<Execution> claim(int slots) {
        List<Execution> claimed = new ArrayList<>();
        for (var order : jobs.lockDue(slots)) {
            boolean manual = order.manualCredits() > 0;
            var now = store.now();
            boolean exhausted =
                    order.attemptCount() >= properties.getMaxAttempts()
                            || !now.isBefore(
                                    order.createdAt()
                                            .plusSeconds(properties.getMaxElapsedSeconds()));
            if (exhausted && !manual) {
                jobs.pause(order.id());
                continue;
            }
            String action =
                    manual || !now.isBefore(order.deadline()) ? "QUERY" : order.nextAction();
            String token = UUID.randomUUID().toString();
            String attemptId = UUID.randomUUID().toString();
            jobs.startAttempt(order.id(), attemptId, token, action, manual);
            claimed.add(new Execution(store.find(order.id()), attemptId, token, action, manual));
        }
        return claimed;
    }

    @Transactional
    public void finish(Execution execution, String outcome) {
        jobs.finishAttempt(execution.attemptId(), outcome);
        var order = store.lock(execution.order().id());
        boolean canIssue =
                !execution.manual()
                        && "NOT_FOUND".equals(outcome)
                        && order.lastVersion() == null
                        && store.now().isBefore(order.deadline());
        int delaySeconds = 1 << Math.min(order.attemptCount(), 5);
        jobs.reschedule(order.id(), execution.token(), canIssue ? "ISSUE" : "QUERY", delaySeconds);
    }

    @Transactional
    public void recoverExpired() {
        jobs.recoverExpired();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void requeryInCurrentTransaction(long orderId) {
        if (!jobs.grantQuery(orderId)) {
            throw new BusinessException(ErrorCode.REPAIR_NOT_ALLOWED);
        }
    }
}
