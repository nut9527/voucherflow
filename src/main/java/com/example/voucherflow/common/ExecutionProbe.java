package com.example.voucherflow.common;

/** 故障演练探针，在指定业务节点通知演练程序当前执行位置。 */
@FunctionalInterface
public interface ExecutionProbe {
    void hit(String point, String identity);
}
