package com.example.voucherflow.fulfillment;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class FulfillmentJobStore {
    private static final String RESCHEDULE =
            """
            UPDATE fulfillment_orders
            SET job_state=CASE WHEN (attempt_count>=? OR created_at<=TIMESTAMPADD(SECOND,-?,UTC_TIMESTAMP(3))
                                    OR manual_execution) AND manual_query_credits=0 THEN 'PAUSED' ELSE 'READY' END,
                next_action=?,next_run_at=TIMESTAMPADD(SECOND,?,UTC_TIMESTAMP(3)),
                execution_token=NULL,lease_until=NULL,revision=revision+1
            WHERE id=?
            """;

    private final JdbcTemplate jdbc;
    private final FulfillmentProperties properties;

    public FulfillmentJobStore(JdbcTemplate jdbc, FulfillmentProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    void register(long id) {
        jdbc.update(
                """
                UPDATE fulfillment_orders SET job_state='READY',revision=revision+1,next_run_at=UTC_TIMESTAMP(3)
                WHERE id=? AND job_state='WAITING_EVENT' AND status IN ('PENDING','UNKNOWN')
                """,
                id);
    }

    List<SupplierStore.Order> lockDue(int limit) {
        return jdbc.query(
                """
                SELECT * FROM fulfillment_orders WHERE job_state='READY' AND next_run_at<=UTC_TIMESTAMP(3)
                ORDER BY next_run_at,id LIMIT ? FOR UPDATE SKIP LOCKED
                """,
                SupplierStore::order,
                limit);
    }

    void pause(long id) {
        jdbc.update(
                "UPDATE fulfillment_orders SET job_state='PAUSED',revision=revision+1 WHERE id=?",
                id);
    }

    void startAttempt(long orderId, String attemptId, String token, String action, boolean manual) {
        jdbc.update(
                """
                UPDATE fulfillment_orders SET status='UNKNOWN',job_state='RUNNING',execution_token=?,
                    lease_until=TIMESTAMPADD(SECOND,?,UTC_TIMESTAMP(3)),attempt_count=attempt_count+1,
                    issue_attempts=issue_attempts+?,query_attempts=query_attempts+?,
                    manual_query_credits=manual_query_credits-?,manual_execution=?,revision=revision+1
                WHERE id=?
                """,
                token,
                properties.getLeaseSeconds(),
                "ISSUE".equals(action) ? 1 : 0,
                "QUERY".equals(action) ? 1 : 0,
                manual ? 1 : 0,
                manual,
                orderId);
        jdbc.update(
                "INSERT INTO fulfillment_attempts(id,order_id,execution_token,action) VALUES(?,?,?,?)",
                attemptId,
                orderId,
                token,
                action);
    }

    void finishAttempt(String attemptId, String outcome) {
        jdbc.update(
                "UPDATE fulfillment_attempts SET outcome=?,finished_at=UTC_TIMESTAMP(3) WHERE id=?",
                outcome,
                attemptId);
    }

    void reschedule(long orderId, String token, String nextAction, int delaySeconds) {
        jdbc.update(
                RESCHEDULE
                        + " AND job_state='RUNNING' AND execution_token=? AND lease_until>UTC_TIMESTAMP(3)",
                properties.getMaxAttempts(),
                properties.getMaxElapsedSeconds(),
                nextAction,
                delaySeconds,
                orderId,
                token);
    }

    void recoverExpired() {
        var expired =
                jdbc.queryForList(
                        """
                SELECT id FROM fulfillment_orders WHERE job_state='RUNNING' AND lease_until<=UTC_TIMESTAMP(3)
                ORDER BY lease_until,id LIMIT 32 FOR UPDATE SKIP LOCKED
                """,
                        Long.class);
        for (long id : expired) {
            jdbc.update(
                    RESCHEDULE,
                    properties.getMaxAttempts(),
                    properties.getMaxElapsedSeconds(),
                    "QUERY",
                    0,
                    id);
        }
    }

    boolean grantQuery(long orderId) {
        return jdbc.update(
                        """
                UPDATE fulfillment_orders SET job_state='READY',next_action='QUERY',next_run_at=UTC_TIMESTAMP(3),
                    manual_query_credits=manual_query_credits+1,manual_query_granted_total=manual_query_granted_total+1,
                    revision=revision+1
                WHERE id=? AND job_state='PAUSED' AND status IN ('PENDING','UNKNOWN') AND manual_query_granted_total<3
                """,
                        orderId)
                == 1;
    }
}
