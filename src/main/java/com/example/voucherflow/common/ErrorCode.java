package com.example.voucherflow.common;

import org.springframework.http.HttpStatus;

/** 定义业务错误与 HTTP 状态、默认中文提示之间的对应关系。 */
public enum ErrorCode {
    VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "请求参数不正确"),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "请先登录"),
    BAD_CREDENTIALS(HttpStatus.UNAUTHORIZED, "用户名或密码错误"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "没有操作权限"),
    CSRF_INVALID(HttpStatus.FORBIDDEN, "请求凭证已失效，请刷新后重试"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "资源不存在"),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "请求方法不受支持"),
    INVALID_CAMPAIGN_STATE(HttpStatus.CONFLICT, "活动状态不允许此操作"),
    CAMPAIGN_NOT_STARTED(HttpStatus.CONFLICT, "活动尚未开始"),
    CAMPAIGN_ENDED(HttpStatus.CONFLICT, "活动已结束"),
    CAMPAIGN_DISABLED(HttpStatus.CONFLICT, "活动已停用"),
    OUT_OF_STOCK(HttpStatus.CONFLICT, "权益已领完"),
    VOUCHER_EXPIRED(HttpStatus.CONFLICT, "权益已过期"),
    SYSTEM_BUSY(HttpStatus.SERVICE_UNAVAILABLE, "系统繁忙，请稍后重试"),
    RESULT_UNKNOWN(HttpStatus.SERVICE_UNAVAILABLE, "结果待确认，请查询原订单"),
    REVISION_CONFLICT(HttpStatus.CONFLICT, "记录已变更，请重新复核"),
    COMMAND_CONFLICT(HttpStatus.CONFLICT, "命令号已用于其他操作"),
    SUPPLIER_EVIDENCE_CONFLICT(HttpStatus.CONFLICT, "供应商回执内容冲突"),
    REPAIR_NOT_ALLOWED(HttpStatus.CONFLICT, "当前证据或状态不允许此操作"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "服务暂时不可用");

    private final HttpStatus status;
    private final String message;

    ErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    public HttpStatus httpStatus() { return status; }
    public String defaultMessage() { return message; }
}
