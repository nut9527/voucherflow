package com.example.vfsupplier;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
/** 实现请求指纹与回调签名，与主应用使用相同的字节编码约定。 */
final class Protocol {
    private Protocol() {}
    /** 将每个字段的 UTF-8 字节长度写入指纹，区分不同的字段分组。 */
    static String fingerprint(String product,String subject) {
        try {
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            for(String field:new String[]{product,subject}) {
                byte[] bytes=field.getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array());digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch(java.security.GeneralSecurityException ex) { throw new IllegalStateException(ex); }
    }
    /** 对时间戳、分隔点和原始请求体签名，避免重新序列化改变验签内容。 */
    static String sign(String secret,String timestamp,byte[] body) {
        try {
            var mac=Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
            mac.update((timestamp+".").getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch(java.security.GeneralSecurityException ex) { throw new IllegalStateException(ex); }
    }
}
