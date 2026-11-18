package com.example.voucherflow.outbox;

import java.util.Map;
import org.slf4j.MDC;

/** 为异步消息处理设置日志上下文，并在结束时恢复线程原有上下文。 */
public final class EventLogContext implements AutoCloseable {
    private final Map<String, String> previous = MDC.getCopyOfContextMap();

    private EventLogContext(DomainEvent event) {
        MDC.clear();
        MDC.put("eventId", event.eventId());
        if (event.traceId() != null) {
            MDC.put("traceId", event.traceId());
        }
        MDC.put("orderId", event.data().get("orderId"));
    }

    public static EventLogContext open(DomainEvent event) {
        return new EventLogContext(event);
    }

    @Override
    public void close() {
        if (previous == null) {
            MDC.clear();
        } else {
            MDC.setContextMap(previous);
        }
    }
}
