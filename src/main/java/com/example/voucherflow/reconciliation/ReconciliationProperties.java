package com.example.voucherflow.reconciliation;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 控制对账扫描每批最多读取的订单数量。 */
@Component
@ConfigurationProperties("voucherflow.reconciliation")
public class ReconciliationProperties {
    private int batchSize = 200;

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    @PostConstruct
    void validate() {
        if (batchSize < 1 || batchSize > 1000) {
            throw new IllegalArgumentException("Reconciliation batch size must be between 1 and 1000");
        }
    }
}
