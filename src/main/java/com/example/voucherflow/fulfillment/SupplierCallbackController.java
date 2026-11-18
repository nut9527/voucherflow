package com.example.voucherflow.fulfillment;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.example.voucherflow.common.ApiResponse;

/** 接收供应商原始回调报文及签名头，交由回调服务验签和存证。 */
@RestController
public class SupplierCallbackController {
    private final SupplierCallbackService service;

    public SupplierCallbackController(SupplierCallbackService service) {
        this.service = service;
    }

    @PostMapping("/api/supplier/callbacks")
    public ApiResponse<Void> callback(
            @RequestHeader("X-Supplier-Timestamp") String timestamp,
            @RequestHeader("X-Supplier-Signature") String signature,
            jakarta.servlet.http.HttpServletRequest request)
            throws java.io.IOException {
        byte[] body = request.getInputStream().readNBytes(16385);
        if (body.length > 16384)
            throw new com.example.voucherflow.common.BusinessException(
                    com.example.voucherflow.common.ErrorCode.FORBIDDEN);
        service.accept(timestamp, signature, body);
        return ApiResponse.ok(null);
    }
}
