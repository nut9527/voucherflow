package com.example.voucherflow.outbox;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.apache.rocketmq.client.apis.message.MessageView;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** 解析并校验事件信封，统一消息路由和消费去重所用的内容摘要。 */
@Component
public class EventCodec {
    public static final String FULFILLMENT_TOPIC = "vf_fulfillment";
    public static final String NOTIFICATION_TOPIC = "vf_notification";
    private final ObjectMapper json;

    public EventCodec(ObjectMapper json) { this.json = json; }

    /** 同时校验信封与消息的主题、标签、键，确保传输身份一致。 */
    public DomainEvent decode(MessageView message) {
        var body = message.getBody().asReadOnlyBuffer();
        if (body.remaining() > 65536) { throw new IllegalArgumentException("Event exceeds size limit"); }
        byte[] bytes = new byte[body.remaining()];
        body.get(bytes);
        var event = decode(new String(bytes, StandardCharsets.UTF_8));
        var route = route(event.eventType());
        if (!route.topic().equals(message.getTopic()) || !message.getTag().orElse("").equals(route.tag())
                || !event.eventId().equals(message.getProperties().get("eventId"))
                || message.getKeys().size() != 1 || !message.getKeys().contains(event.eventId())) {
            throw new IllegalArgumentException("Event transport identity mismatch");
        }
        return event;
    }

    public DomainEvent decode(String payload) {
        if (payload.getBytes(StandardCharsets.UTF_8).length > 65536) {
            throw new IllegalArgumentException("Event exceeds size limit");
        }
        var tree = json.readTree(payload);
        if (!tree.isObject() || tree.size() != 8) { throw new IllegalArgumentException("Invalid event envelope"); }
        for (String field : Set.of("eventId", "eventType", "aggregateType", "aggregateId", "occurredAt")) {
            if (!tree.path(field).isString()) { throw new IllegalArgumentException("Invalid event field type"); }
        }
        if (!tree.path("schemaVersion").isInt() || !tree.path("data").isObject()
                || !(tree.path("traceId").isNull() || tree.path("traceId").isString())) {
            throw new IllegalArgumentException("Invalid event field type");
        }
        for (var value : tree.path("data")) {
            if (!value.isString()) { throw new IllegalArgumentException("Invalid event reference type"); }
        }
        var event = json.treeToValue(tree, DomainEvent.class);
        validate(event);
        return event;
    }

    /** 对字段和业务引用排序后计算摘要，使 JSON 属性顺序不影响消费去重。 */
    public String payloadHash(DomainEvent event) {
        validate(event);
        var canonical = new TreeMap<String, Object>();
        canonical.put("eventId", event.eventId()); canonical.put("eventType", event.eventType());
        canonical.put("schemaVersion", event.schemaVersion()); canonical.put("aggregateType", event.aggregateType());
        canonical.put("aggregateId", event.aggregateId()); canonical.put("occurredAt", event.occurredAt().toString());
        canonical.put("traceId", event.traceId()); canonical.put("data", new TreeMap<>(event.data()));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(json.writeValueAsBytes(canonical)));
        } catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }

    private void validate(DomainEvent event) {
        if (event.schemaVersion() != 1 || event.occurredAt() == null || event.data() == null) {
            throw new IllegalArgumentException("Unsupported event schema");
        }
        UUID.fromString(event.eventId());
        route(event.eventType());
        var data = event.data();
        var expected = switch (event.eventType()) {
            case "VoucherIssued.v1" -> Set.of("userId", "orderId", "voucherId");
            case "FulfillmentRequested.v1" -> Set.of("userId", "orderId", "campaignId");
            case "FulfillmentFailed.v1" -> Set.of("userId", "orderId");
            default -> throw new IllegalArgumentException("Unsupported event type");
        };
        if (!data.keySet().equals(expected)) { throw new IllegalArgumentException("Invalid event data"); }
        data.values().forEach(value -> {
            if (value == null || !value.matches("[1-9][0-9]{0,18}") || Long.parseLong(value) <= 0) {
                throw new IllegalArgumentException("Invalid event reference");
            }
        });
        boolean issued = event.eventType().equals("VoucherIssued.v1");
        if (!(issued ? "USER_VOUCHER" : "FULFILLMENT_ORDER").equals(event.aggregateType())
                || !event.aggregateId().equals(data.get(issued ? "voucherId" : "orderId"))) {
            throw new IllegalArgumentException("Invalid aggregate reference");
        }
        if (event.traceId() != null && event.traceId().length() > 128) {
            throw new IllegalArgumentException("Invalid trace reference");
        }
    }

    public static Route route(String type) {
        return switch (type) {
            case "FulfillmentRequested.v1" -> new Route(FULFILLMENT_TOPIC, "FulfillmentRequestedV1");
            case "VoucherIssued.v1" -> new Route(NOTIFICATION_TOPIC, "VoucherIssuedV1");
            case "FulfillmentFailed.v1" -> new Route(NOTIFICATION_TOPIC, "FulfillmentFailedV1");
            default -> throw new IllegalArgumentException("Unsupported event type");
        };
    }
    public record Route(String topic, String tag) {}
}
