package com.example.voucherflow.outbox;
import java.time.Instant;
import java.util.Map;
/** 统一领域事件信封，携带业务引用、发生时间和请求追踪标识。 */
public record DomainEvent(String eventId, String eventType, int schemaVersion, String aggregateType,
        String aggregateId, Instant occurredAt, String traceId, Map<String, String> data) {}
