package com.example.voucherflow.outbox;
import com.example.voucherflow.outbox.mapper.OutboxMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
/** 将领域事件写入调用方事务，使业务变更与待发布事件一起提交。 */
@Service
public class OutboxEventService {
    private final OutboxMapper mapper;
    private final ObjectMapper json;
    public OutboxEventService(OutboxMapper mapper, ObjectMapper json) { this.mapper = mapper; this.json = json; }
    /** 必须加入业务事务；事务回滚时待发布事件也随之撤销。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void appendInCurrentTransaction(DomainEvent event) {
        mapper.insert(event, json.writeValueAsString(event));
    }
}
