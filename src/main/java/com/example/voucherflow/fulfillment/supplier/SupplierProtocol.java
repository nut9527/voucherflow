package com.example.voucherflow.fulfillment.supplier;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** 定义供应商请求、结果和回调格式，提供字段摘要与回调签名算法。 */
public final class SupplierProtocol {
    private SupplierProtocol() {}
    public record IssueRequest(String requestNo, String productCode, String subjectRef) {}
    public record Result(String requestNo, String productCode, String subjectRef, String status,
                         Long resultVersion, String voucherRef) {
        public boolean terminal() { return "SUCCEEDED".equals(status) || "REJECTED".equals(status); }
        public void validate() {
            boolean valid = switch (status) {
                case "NOT_FOUND" -> resultVersion == null && voucherRef == null;
                case "ACCEPTED", "REJECTED" -> resultVersion != null && resultVersion > 0 && voucherRef == null;
                case "SUCCEEDED" -> resultVersion != null && resultVersion > 0 && voucherRef != null
                        && !voucherRef.isBlank() && voucherRef.length() <= 128;
                default -> false;
            };
            if (!valid) throw new IllegalArgumentException("Invalid supplier result");
        }
    }
    public record Callback(String eventId, String requestNo, String productCode, String subjectRef,
                           String status, Long resultVersion, String voucherRef) {
        public Result result() { return new Result(requestNo, productCode, subjectRef, status, resultVersion, voucherRef); }
    }
    /** 每个字段先编码长度再编码内容，区分拼接边界、空串与空值。 */
    public static String fingerprint(String... fields) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (String field : fields) {
                byte[] value = field == null ? new byte[0] : field.getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(4).putInt(field == null ? -1 : value.length).array());
                digest.update(value);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
    public static String resultHash(Result r) {
        return fingerprint(r.requestNo(), r.productCode(), r.subjectRef(), r.status(),
                r.resultVersion() == null ? null : r.resultVersion().toString(), r.voucherRef());
    }
    /** 以“时间戳.原始报文”为签名内容，与供应商回调协议保持一致。 */
    public static String sign(String secret, String timestamp, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update((timestamp + ".").getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch (java.security.GeneralSecurityException ex) { throw new IllegalStateException(ex); }
    }
}
