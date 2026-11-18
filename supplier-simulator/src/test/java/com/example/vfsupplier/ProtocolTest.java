package com.example.vfsupplier;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** 验证指纹与公开协议向量一致，并能区分字段边界。 */
class ProtocolTest {
    @Test
    void matchesPublishedFingerprintVector() {
        assertThat(Protocol.fingerprint("课程", "user:1"))
                .isEqualTo("c67cfc039fefc1f573be4f0f875a2da522bbfd0c2f6f66f133c3d5112076b62f");
    }

    @Test
    void partitionsCannotCollide() {
        assertThat(Protocol.fingerprint("ab", "c")).isNotEqualTo(Protocol.fingerprint("a", "bc"));
    }
}
