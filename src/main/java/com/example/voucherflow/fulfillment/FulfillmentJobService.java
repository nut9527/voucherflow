package com.example.voucherflow.fulfillment;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** 管理供应商履约任务的领取、执行租约、重试预算和人工查单额度。 */
@Service
public class FulfillmentJobService {
    private final SupplierStore store;
    private final FulfillmentProperties properties;
    private final TransactionTemplate tx;
    public record Execution(SupplierStore.Order order,String attemptId,String token,String action,boolean manual) {}
    public FulfillmentJobService(SupplierStore store,FulfillmentProperties properties,TransactionTemplate tx) {
        this.store=store;this.properties=properties;this.tx=tx;
    }
    /** 在短事务内领取到期任务，并记录本次执行令牌和尝试记录。 */
    public List<Execution> claim(int slots) {
        return tx.execute(status -> {
            List<Execution> claimed=new ArrayList<>();
            for(var order:store.due(slots)) {
                boolean manual=order.manualCredits()>0;
                boolean exhausted=order.attemptCount()>=properties.getMaxAttempts()
                        || !store.now().isBefore(order.createdAt().plusSeconds(properties.getMaxElapsedSeconds()));
                // 耗尽的是自动执行预算，不是业务订单：暂停任务仍保留预留库存，等待明确结果或人工查单。
                if(exhausted && !manual) {
                    store.jdbc().update("UPDATE fulfillment_orders SET job_state='PAUSED',revision=revision+1 WHERE id=?",order.id());
                    continue;
                }
                // 超出供应商幂等窗口或使用人工额度时只查单，避免再次发放。
                String action=manual || !store.now().isBefore(order.deadline()) ? "QUERY" : order.nextAction();
                String token=UUID.randomUUID().toString(), attempt=UUID.randomUUID().toString();
                // 领取时就计入尝试，人工任务同时扣减额度，即使请求发出后进程退出，该次执行也不能无限重获预算。
                store.jdbc().update("UPDATE fulfillment_orders SET status='UNKNOWN',job_state='RUNNING',execution_token=?,lease_until=TIMESTAMPADD(SECOND,?,UTC_TIMESTAMP(3)),attempt_count=attempt_count+1,issue_attempts=issue_attempts+?,query_attempts=query_attempts+?,manual_query_credits=manual_query_credits-?,manual_execution=?,revision=revision+1 WHERE id=?",
                        token,properties.getLeaseSeconds(),"ISSUE".equals(action)?1:0,"QUERY".equals(action)?1:0,manual?1:0,manual,order.id());
                store.jdbc().update("INSERT INTO fulfillment_attempts(id,order_id,execution_token,action) VALUES(?,?,?,?)",attempt,order.id(),token,action);
                claimed.add(new Execution(store.find(order.id()),attempt,token,action,manual));
            }
            return claimed;
        });
    }
    /** 保存尝试结果，仅当前有效租约可安排下一次执行。 */
    public void finish(Execution execution,String outcome) {
        tx.executeWithoutResult(status -> {
            // 每次尝试都保留结果记录；下方订单更新另行校验租约，过期执行只能留痕，不能重排任务。
            store.jdbc().update("UPDATE fulfillment_attempts SET outcome=?,finished_at=UTC_TIMESTAMP(3) WHERE id=?",outcome,execution.attemptId());
            var order=store.lock(execution.order().id());
            // 自动任务从未收到结果版本、当前未查到记录且仍在幂等窗口内时，才可再次发放。
            String next=!execution.manual() && "NOT_FOUND".equals(outcome) && order.lastVersion()==null && store.now().isBefore(order.deadline()) ? "ISSUE":"QUERY";
            // 人工查单是一份独立额度，用完后重新暂停，不因一次人工操作重新开启整轮自动重试。
            store.jdbc().update("UPDATE fulfillment_orders SET job_state=CASE WHEN (attempt_count>=? OR created_at<=TIMESTAMPADD(SECOND,-?,UTC_TIMESTAMP(3)) OR ?) AND manual_query_credits=0 THEN 'PAUSED' ELSE 'READY' END,next_action=?,next_run_at=TIMESTAMPADD(SECOND,?,UTC_TIMESTAMP(3)),execution_token=NULL,lease_until=NULL,revision=revision+1 WHERE id=? AND job_state='RUNNING' AND execution_token=? AND lease_until>UTC_TIMESTAMP(3)",
                    properties.getMaxAttempts(),properties.getMaxElapsedSeconds(),execution.manual(),next,Math.min(60,1<<Math.min(order.attemptCount(),5)),order.id(),execution.token());
        });
    }
    /** 过期执行可能已在供应商侧生效，因此恢复后统一先查单。 */
    public void recoverExpired() {
        tx.executeWithoutResult(status -> {
            var ids=store.jdbc().queryForList("SELECT id FROM fulfillment_orders WHERE job_state='RUNNING' AND lease_until<=UTC_TIMESTAMP(3) ORDER BY lease_until,id LIMIT 32 FOR UPDATE SKIP LOCKED",Long.class);
            for(long id:ids) store.jdbc().update("UPDATE fulfillment_orders SET job_state=CASE WHEN (attempt_count>=? OR created_at<=TIMESTAMPADD(SECOND,-?,UTC_TIMESTAMP(3)) OR manual_execution) AND manual_query_credits=0 THEN 'PAUSED' ELSE 'READY' END,next_action='QUERY',next_run_at=UTC_TIMESTAMP(3),execution_token=NULL,lease_until=NULL,revision=revision+1 WHERE id=?",properties.getMaxAttempts(),properties.getMaxElapsedSeconds(),id);
        });
    }
    /** 人工修复为暂停订单授予一次查单机会，累计额度由数据库条件限制。 */
    @Transactional(propagation=Propagation.MANDATORY)
    public void requeryInCurrentTransaction(long orderId) {
        int updated=store.jdbc().update("UPDATE fulfillment_orders SET job_state='READY',next_action='QUERY',next_run_at=UTC_TIMESTAMP(3),manual_query_credits=manual_query_credits+1,manual_query_granted_total=manual_query_granted_total+1,revision=revision+1 WHERE id=? AND job_state='PAUSED' AND status IN ('PENDING','UNKNOWN') AND manual_query_granted_total<3",orderId);
        if(updated!=1) throw new com.example.voucherflow.common.BusinessException(com.example.voucherflow.common.ErrorCode.REPAIR_NOT_ALLOWED);
    }
}
