package com.example.voucherflow;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** 主应用入口，启动权益领取、履约、核销与对账服务。 */
@SpringBootApplication
public class VoucherFlowApplication {

    public static void main(String[] args) {
        SpringApplication.run(VoucherFlowApplication.class, args);
    }
}
