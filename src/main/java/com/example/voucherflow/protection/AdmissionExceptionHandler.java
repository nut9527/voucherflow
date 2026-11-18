package com.example.voucherflow.protection;

import com.example.voucherflow.common.ApiResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 将准入失败转换为 HTTP 响应，并通过 Retry-After 告知重试间隔。 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AdmissionExceptionHandler {
    @ExceptionHandler(AdmissionException.class)
    ResponseEntity<ApiResponse<Void>> handle(AdmissionException ex) {
        return ResponseEntity.status(ex.status())
                .header("Retry-After", Long.toString(ex.retryAfterSeconds()))
                .body(ex.response());
    }
}
