package com.example.voucherflow.reconciliation;

import com.example.voucherflow.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** 提供管理员查询活动库存、订单、券与核销账目一致性的接口。 */
@RestController
public class CampaignStatsController {
    private final LocalReconciliationService service;

    public CampaignStatsController(LocalReconciliationService service) {
        this.service = service;
    }

    @GetMapping("/api/admin/campaigns/{id}/stats")
    public ApiResponse<LocalStats> stats(@PathVariable long id) {
        return ApiResponse.ok(service.checkCampaign(id));
    }
}
