package com.example.voucherflow.outbox;

import com.example.voucherflow.common.BusinessException;
import com.example.voucherflow.common.ErrorCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 在修复事务中为失败事件补充有限的人工发布额度。 */
@Service
public class OutboxReplayService {
    private final JdbcTemplate jdbc;

    public OutboxReplayService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 每次授予三次发布机会，保留原事件编号和信封以延续消费幂等语义。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void replayInCurrentTransaction(long orderId, String targetEventId, String commandId) {
        // 累计授权最多六次：每次增加三次且增加前最多为三；原尝试计数继续保留。
        // 同一修复命令的重复授权由外层修复事务的命令回执拦截，此处负责事件自身的总额度。
        if (jdbc.update(
                        """
                UPDATE outbox_events SET state='PENDING',manual_publish_credits=manual_publish_credits+3,
                manual_publish_granted_total=manual_publish_granted_total+3,next_attempt_at=UTC_TIMESTAMP(3),last_error=NULL
                WHERE id=? AND state='FAILED' AND manual_publish_granted_total<=3
                AND JSON_UNQUOTE(JSON_EXTRACT(payload,'$.data.orderId'))=?
                """,
                        targetEventId,
                        Long.toString(orderId))
                != 1) {
            throw new BusinessException(ErrorCode.REPAIR_NOT_ALLOWED);
        }
    }
}
