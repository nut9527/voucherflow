package com.example.voucherflow.outbox;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.apache.rocketmq.client.apis.message.MessageView;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 验证事件编码、内容指纹、传输元数据和异步日志上下文的契约。 */
class EventCodecTest {
    private final JsonMapper json = JsonMapper.builder().findAndAddModules().build();
    private final EventCodec codec = new EventCodec(json);
    private DomainEvent event(Map<String, String> data) {
        return new DomainEvent("3e9fd805-8b20-4a5b-99d5-810f2b903e31", "VoucherIssued.v1", 1,
                "USER_VOUCHER", "3", Instant.EPOCH, null, data);
    }
    @Test void canonicalHashIgnoresKeyOrderButPreservesBusinessContent() {
        var first = new LinkedHashMap<String, String>();
        first.put("orderId", "1"); first.put("userId", "2"); first.put("voucherId", "3");
        var second = new LinkedHashMap<String, String>();
        second.put("voucherId", "3"); second.put("userId", "2"); second.put("orderId", "1");
        assertThat(codec.payloadHash(event(first))).isEqualTo(codec.payloadHash(event(second)));
        second.put("userId", "4");
        assertThat(codec.payloadHash(event(first))).isNotEqualTo(codec.payloadHash(event(second)));
    }
    @Test void supportedEnvelopeRoundTripsAndRejectsUnsupportedSchemas() {
        var event = event(Map.of("orderId", "1", "userId", "2", "voucherId", "3"));
        String payload = json.writeValueAsString(event);
        assertThat(codec.decode(payload)).isEqualTo(event);
        assertThatThrownBy(() -> codec.decode(payload.replace("\"schemaVersion\":1", "\"schemaVersion\":2")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> codec.decode(payload.replace("\"userId\":\"2\"", "\"userId\":2")))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void transportMustMatchBusinessIdentityAndDoesNotConsumeBuffer() {
        var event = event(Map.of("orderId", "1", "userId", "2", "voucherId", "3"));
        var buffer = ByteBuffer.wrap(json.writeValueAsString(event).getBytes(StandardCharsets.UTF_8));
        var message = mock(MessageView.class);
        when(message.getBody()).thenReturn(buffer);
        when(message.getTopic()).thenReturn(EventCodec.NOTIFICATION_TOPIC);
        when(message.getTag()).thenReturn(Optional.of("VoucherIssuedV1"));
        when(message.getProperties()).thenReturn(Map.of("eventId", event.eventId()));
        when(message.getKeys()).thenReturn(Set.of(event.eventId()));
        assertThat(codec.decode(message)).isEqualTo(event);
        assertThat(buffer.position()).isZero();
        when(message.getTag()).thenReturn(Optional.of("FulfillmentFailedV1"));
        assertThatThrownBy(() -> codec.decode(message)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void oversizedAndUnrelatedReferencesAreRejected() {
        assertThatThrownBy(() -> codec.decode(" ".repeat(65537))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> codec.payloadHash(event(Map.of("orderId", "1", "userId", "2", "voucherId", "4"))))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void retryDelayRemainsBoundedWithoutIntegerOverflow() {
        assertThat(OutboxLeaseService.backoffMillis(1)).isBetween(1000L, 1250L);
        assertThat(OutboxLeaseService.backoffMillis(Integer.MAX_VALUE)).isBetween(60000L, 60250L);
    }
    @Test void asynchronousContextRestoresPriorThreadValues() {
        org.slf4j.MDC.put("traceId", "outer-trace");
        var event = event(Map.of("orderId", "1", "userId", "2", "voucherId", "3"));
        try {
            try (var context = EventLogContext.open(event)) {
                assertThat(org.slf4j.MDC.get("eventId")).isEqualTo(event.eventId());
                assertThat(org.slf4j.MDC.get("orderId")).isEqualTo("1");
                assertThat(org.slf4j.MDC.get("traceId")).isNull();
            }
            assertThat(org.slf4j.MDC.get("traceId")).isEqualTo("outer-trace");
            assertThat(org.slf4j.MDC.get("eventId")).isNull();
        } finally { org.slf4j.MDC.clear(); }
    }
}
