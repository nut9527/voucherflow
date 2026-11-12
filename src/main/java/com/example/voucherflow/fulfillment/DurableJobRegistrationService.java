package com.example.voucherflow.fulfillment;

import com.example.voucherflow.outbox.DomainEvent;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 将履约请求事件转换为数据库中的待执行任务，供消息确认后继续处理。 */
@Service
public class DurableJobRegistrationService {
    private final SupplierStore store;
    public DurableJobRegistrationService(SupplierStore store) { this.store=store; }
    /** 消费去重与任务就绪状态共用事务，消息确认后任务仍可由执行器继续领取。 */
    @Transactional(propagation=Propagation.MANDATORY)
    public void registerRequested(DomainEvent event) {
        if(!"FulfillmentRequested.v1".equals(event.eventType()) || !"FULFILLMENT_ORDER".equals(event.aggregateType())) throw new IllegalArgumentException("Unexpected fulfillment event");
        long id=Long.parseLong(event.aggregateId());
        var order=store.lock(id);
        if(order.supplierId()==null || !Long.toString(id).equals(event.data().get("orderId"))
                || !Long.toString(order.userId()).equals(event.data().get("userId"))
                || !Long.toString(order.campaignId()).equals(event.data().get("campaignId"))) throw new IllegalArgumentException("Invalid fulfillment event reference");
        store.jdbc().update("UPDATE fulfillment_orders SET job_state='READY',revision=revision+1,next_run_at=UTC_TIMESTAMP(3) WHERE id=? AND job_state='WAITING_EVENT' AND status IN ('PENDING','UNKNOWN')",id);
    }
}
