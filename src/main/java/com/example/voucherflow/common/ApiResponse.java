package com.example.voucherflow.common;

import org.slf4j.MDC;

/** 统一接口响应，携带业务状态、数据及当前请求的追踪编号。 */
public record ApiResponse<T>(String code, String message, T data, String traceId) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>("OK", "成功", data, MDC.get("traceId"));
    }

    public static ApiResponse<Void> error(String code, String message) {
        return new ApiResponse<>(code, message, null, MDC.get("traceId"));
    }
}
