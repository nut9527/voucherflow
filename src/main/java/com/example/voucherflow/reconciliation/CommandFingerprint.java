package com.example.voucherflow.reconciliation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** 对修复目标、操作人和命令内容计算摘要，识别同一命令编号的冲突复用。 */
@Component
public class CommandFingerprint {
    private final ObjectMapper json;

    public CommandFingerprint(ObjectMapper json) {
        this.json = json;
    }

    public String calculate(
            long diffId,
            long orderId,
            String eventId,
            long operatorId,
            ReconciliationDtos.RepairRequest request) {
        var encoded =
                json.writeValueAsString(
                        Arrays.asList(
                                diffId,
                                orderId,
                                eventId,
                                operatorId,
                                request.expectedRevision(),
                                request.operation().name(),
                                request.reason()));
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(encoded.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
