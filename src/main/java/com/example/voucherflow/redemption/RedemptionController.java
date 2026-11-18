package com.example.voucherflow.redemption;

import com.example.voucherflow.common.ApiResponse;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** 提供券核销入口，操作者权限和幂等处理由核销服务统一完成。 */
@RestController
public class RedemptionController {
    private final RedemptionService service;

    public RedemptionController(RedemptionService service) {
        this.service = service;
    }

    @PostMapping("/api/vouchers/{id}/redeem")
    public ApiResponse<VoucherDtos.RedemptionReceipt> redeem(@PathVariable long id) {
        return ApiResponse.ok(service.redeem(id));
    }
}
