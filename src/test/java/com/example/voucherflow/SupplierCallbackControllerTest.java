package com.example.voucherflow;

import com.example.voucherflow.common.BusinessException;
import com.example.voucherflow.common.ErrorCode;
import com.example.voucherflow.fulfillment.SupplierCallbackController;
import com.example.voucherflow.fulfillment.SupplierCallbackService;
import com.example.voucherflow.fulfillment.FulfillmentProperties;
import com.example.voucherflow.fulfillment.SupplierEvidenceService;
import com.example.voucherflow.fulfillment.SupplierStore;
import com.example.voucherflow.fulfillment.supplier.SupplierProtocol;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.AdditionalMatchers.aryEq;

/** 验证回调请求的原始字节传递、请求体大小边界及未知订单处理。 */
class SupplierCallbackControllerTest {
    @Test void signedUnknownOrderIsNotFoundAndDoesNotRecordEvidence() {
        var properties = new FulfillmentProperties();
        properties.setEnabled(true);
        properties.setCallbackSecret("test-only-supplier-callback-secret");
        var store = mock(SupplierStore.class);
        var evidence = mock(SupplierEvidenceService.class);
        var json = new ObjectMapper();
        var clock = Clock.fixed(Instant.ofEpochSecond(1000), ZoneOffset.UTC);
        var service = new SupplierCallbackService(properties, store, evidence, json, clock);
        byte[] body = json.writeValueAsBytes(new SupplierProtocol.Callback(
                "event-1", "missing", "course", "user:1", "SUCCEEDED", 2L, "voucher-1"));
        when(store.byRequest("missing")).thenThrow(new EmptyResultDataAccessException(1));
        String signature = SupplierProtocol.sign(properties.getCallbackSecret(), "1000", body);
        assertThatThrownBy(() -> service.accept("1000", signature, body))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.code()).isEqualTo(ErrorCode.NOT_FOUND));
        verifyNoInteractions(evidence);
    }

    @Test void chunkedOversizedBodyStopsReadingAtLimitBeforeSignatureService() throws Exception {
        var service=mock(SupplierCallbackService.class);
        var request=mock(HttpServletRequest.class);
        class CountingInput extends ServletInputStream {
            int consumed;
            public int read() { return consumed++<2_000_000?'x':-1; }
            public boolean isFinished() { return consumed>=2_000_000; }
            public boolean isReady() { return true; }
            public void setReadListener(ReadListener listener) {}
        }
        var input=new CountingInput();when(request.getInputStream()).thenReturn(input);
        when(request.getContentLengthLong()).thenReturn(-1L);
        assertThatThrownBy(()->new SupplierCallbackController(service).callback("100","signature",request))
                .isInstanceOfSatisfying(BusinessException.class,error->assertThat(error.code()).isEqualTo(ErrorCode.FORBIDDEN));
        assertThat(input.consumed).isEqualTo(16385);verifyNoInteractions(service);
    }
    @Test void signatureReceivesOriginalBytesWithoutParsingOrNormalization() throws Exception {
        var service=mock(SupplierCallbackService.class);
        var request=new MockHttpServletRequest();
        byte[] bytes="{ \"eventId\" : \"original\" }\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        request.setContent(bytes);
        new SupplierCallbackController(service).callback("100","signature",request);
        verify(service).accept(eq("100"),eq("signature"),aryEq(bytes));
    }
}
