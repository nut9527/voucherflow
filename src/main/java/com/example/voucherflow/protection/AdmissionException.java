package com.example.voucherflow.protection;

import com.example.voucherflow.common.ApiResponse;

/** 表达请求被限流或限流服务不可用，并携带客户端重试等待时间。 */
public final class AdmissionException extends RuntimeException {
    private final int status;
    private final String code;
    private final long retryAfterSeconds;

    private AdmissionException(int status, String code, String message, long retryAfterSeconds) {
        super(message);
        this.status = status;
        this.code = code;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    /** 将毫秒等待时间向上取整为秒，匹配 Retry-After 响应头的单位。 */
    public static AdmissionException throttled(long retryAfterMillis) {
        return new AdmissionException(
                429, "RATE_LIMITED", "请求过于频繁，请稍后重试", Math.max(1, (retryAfterMillis + 999) / 1000));
    }

    public static AdmissionException unavailable() {
        return new AdmissionException(503, "RATE_LIMIT_UNAVAILABLE", "请求保护服务暂时不可用", 1);
    }

    public int status() {
        return status;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }

    public ApiResponse<Void> response() {
        return ApiResponse.error(code, getMessage());
    }
}
