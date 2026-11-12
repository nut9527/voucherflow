package com.example.voucherflow.outbox;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.StaticSessionCredentialsProvider;
import tools.jackson.databind.json.JsonMapper;

/** 离线重放已归档的死信消息，保持原事件信封和消费者位点不变。 */
public final class DlqReplayTool {
    private DlqReplayTool() {}
    public static void main(String[] args) throws Exception {
        if (args.length != 2) { throw new IllegalArgumentException("Usage: archive.jsonl proxy-host:port"); }
        var mapper = JsonMapper.builder().findAndAddModules().build();
        var codec = new EventCodec(mapper);
        List<Archive> records;
        try (var lines = Files.lines(Path.of(args[0]), StandardCharsets.UTF_8)) {
            records = lines.filter(line -> !line.isBlank()).limit(1001)
                    .map(line -> mapper.readValue(line, Archive.class)).toList();
        }
        if (records.isEmpty() || records.size() > 1000) { throw new IllegalArgumentException("Archive must contain 1–1000 records"); }
        // 全部归档通过校验后才开始发送，重放时保留原事件编号以复用消费去重。
        for (var record : records) { validate(record, codec); }
        var provider = ClientServiceProvider.loadService();
        var config = ClientConfiguration.newBuilder().setEndpoints(args[1]).setRequestTimeout(Duration.ofSeconds(5))
                .enableSsl(Boolean.parseBoolean(System.getenv().getOrDefault("VF_MQ_TLS", "false")));
        String key = System.getenv("VF_MQ_ACCESS_KEY");
        if (key != null && !key.isBlank()) {
            config.setCredentialProvider(new StaticSessionCredentialsProvider(key, System.getenv("VF_MQ_SECRET_KEY")));
        }
        try (var producer = provider.newProducerBuilder().setClientConfiguration(config.build()).setMaxAttempts(1)
                .setTopics(EventCodec.FULFILLMENT_TOPIC, EventCodec.NOTIFICATION_TOPIC).build()) {
            for (var record : records) {
                var event = codec.decode(record.body());
                var route = EventCodec.route(event.eventType());
                var receipt = producer.send(provider.newMessageBuilder().setTopic(route.topic()).setTag(route.tag())
                        .setKeys(event.eventId()).addProperty("eventId", event.eventId())
                        .setBody(record.body().getBytes(StandardCharsets.UTF_8)).build());
                System.out.printf("eventId=%s messageId=%s%n", event.eventId(), receipt.getMessageId());
            }
        }
    }
    static void validate(Archive archive, EventCodec codec) throws Exception {
        var now = Instant.now();
        if (archive.archivedAt() == null || archive.archivedAt().isBefore(now.minus(Duration.ofDays(7)))
                || archive.archivedAt().isAfter(now.plusSeconds(60))) {
            throw new IllegalArgumentException("Archive is outside the replay window; reconcile before recovery");
        }
        String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(archive.body().getBytes(StandardCharsets.UTF_8)));
        if (!actual.equals(archive.bodySha256())) { throw new IllegalArgumentException("Archive body checksum mismatch"); }
        var event = codec.decode(archive.body());
        String expected = event.eventType().equals("FulfillmentRequested.v1") ? "vf_fulfillment_worker" : "vf_notification_worker";
        if (!expected.equals(archive.consumerGroup())) { throw new IllegalArgumentException("Archive consumer group mismatch"); }
    }
    public record Archive(String consumerGroup, String messageId, String body, String bodySha256, Instant archivedAt) {}
}
