package com.example.voucherflow.campaign.controller;

import com.example.voucherflow.campaign.CampaignDtos;
import com.example.voucherflow.campaign.CampaignService;
import com.example.voucherflow.common.ApiResponse;
import com.example.voucherflow.common.PageQuery;
import com.example.voucherflow.common.PageResult;
import jakarta.validation.constraints.Positive;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 提供公开活动列表和详情接口，草稿由查询层过滤。 */
@RestController
@RequestMapping("/api/campaigns")
public class CampaignController {
    private final CampaignService service;

    public CampaignController(CampaignService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<PageResult<CampaignDtos.Summary>> page(
            @RequestParam(defaultValue = "0") long afterId,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(service.publicPage(new PageQuery(afterId, size)));
    }

    @GetMapping("/{id}")
    public ApiResponse<CampaignDtos.Detail> detail(@PathVariable @Positive long id) {
        return ApiResponse.ok(service.publicDetail(id));
    }
}

