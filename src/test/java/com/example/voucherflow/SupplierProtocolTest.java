package com.example.voucherflow;
import com.example.voucherflow.fulfillment.supplier.SupplierProtocol;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
/** 验证供应商请求指纹、原始请求体签名和未决状态的协议语义。 */
class SupplierProtocolTest {
    @Test void fingerprintIsUnambiguousAndUsesUtf8Lengths() {
        assertThat(SupplierProtocol.fingerprint("ab","c")).isNotEqualTo(SupplierProtocol.fingerprint("a","bc"));
        assertThat(SupplierProtocol.fingerprint("课程","user:1")).isEqualTo("c67cfc039fefc1f573be4f0f875a2da522bbfd0c2f6f66f133c3d5112076b62f");
    }
    @Test void unknownCannotBeMistakenForRejection() {
        new SupplierProtocol.Result("r","p","s","NOT_FOUND",null,null).validate();
        assertThatThrownBy(()->new SupplierProtocol.Result("r","p","s","NOT_FOUND",1L,null).validate()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new SupplierProtocol.Result("r","p","s","SUCCEEDED",2L,null).validate()).isInstanceOf(IllegalArgumentException.class);
        assertThat(new SupplierProtocol.Result("r","p","s","ACCEPTED",1L,null).terminal()).isFalse();
    }
    @Test void signaturesCoverTimestampAndRawBody() {
        byte[] body="{\"status\":\"SUCCEEDED\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String signature=SupplierProtocol.sign("test-only-secret","100",body);
        assertThat(signature).hasSize(64);
        assertThat(SupplierProtocol.sign("test-only-secret","101",body)).isNotEqualTo(signature);
        assertThat(SupplierProtocol.sign("test-only-secret","100",new byte[]{1})).isNotEqualTo(signature);
    }
}
