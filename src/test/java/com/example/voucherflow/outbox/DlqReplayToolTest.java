package com.example.voucherflow.outbox;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
/** 验证死信归档保留原消息，并检查内容摘要、消费组与有效时间。 */
class DlqReplayToolTest {
    @Test void archivePreservesPayloadAndRejectsTamperingOrWrongGroup() throws Exception {
        var json = JsonMapper.builder().findAndAddModules().build();
        var codec = new EventCodec(json);
        var event = new DomainEvent(UUID.randomUUID().toString(), "VoucherIssued.v1", 1, "USER_VOUCHER", "3",
                Instant.EPOCH, null, Map.of("userId", "1", "orderId", "2", "voucherId", "3"));
        String body = json.writeValueAsString(event);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8)));
        var archive = new DlqReplayTool.Archive("vf_notification_worker", "native-id", body, hash, Instant.now());
        DlqReplayTool.validate(archive, codec);
        assertThat(codec.decode(archive.body())).isEqualTo(event);
        assertThatThrownBy(() -> DlqReplayTool.validate(new DlqReplayTool.Archive("vf_notification_worker", "native-id", body + " ", hash, Instant.now()), codec))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DlqReplayTool.validate(new DlqReplayTool.Archive("vf_fulfillment_worker", "native-id", body, hash, Instant.now()), codec))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DlqReplayTool.validate(new DlqReplayTool.Archive("vf_notification_worker", "native-id", body, hash,
                Instant.now().minusSeconds(8 * 86400)), codec)).isInstanceOf(IllegalArgumentException.class);
    }
}
