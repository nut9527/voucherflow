package com.example.voucherflow.campaign.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.voucherflow.campaign.CampaignDtos;
import com.example.voucherflow.campaign.CampaignService;
import com.example.voucherflow.common.ApiResponse;
import com.example.voucherflow.common.PageQuery;
import com.example.voucherflow.common.PageResult;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;

/** 提供管理员创建、编辑、发布、停用和查询活动的接口。 */
@RestController
@RequestMapping("/api/admin/campaigns")
public class AdminCampaignController {
    private final CampaignService service;

    public AdminCampaignController(CampaignService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<CampaignDtos.Created>> create(
            @Valid @RequestBody CampaignDtos.DraftRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(service.create(request)));
    }

    @PutMapping("/{id}")
    public ApiResponse<CampaignDtos.AdminDetail> update(
            @PathVariable @Positive long id,
            @Valid @RequestBody CampaignDtos.DraftRequest request) {
        return ApiResponse.ok(service.update(id, request));
    }

    @PostMapping("/{id}/publish")
    public ApiResponse<CampaignDtos.AdminDetail> publish(@PathVariable @Positive long id) {
        return ApiResponse.ok(service.publish(id));
    }

    @PostMapping("/{id}/disable")
    public ApiResponse<CampaignDtos.AdminDetail> disable(@PathVariable @Positive long id) {
        return ApiResponse.ok(service.disable(id));
    }

    @GetMapping("/{id}")
    public ApiResponse<CampaignDtos.AdminDetail> detail(@PathVariable @Positive long id) {
        return ApiResponse.ok(service.adminDetail(id));
    }

    @GetMapping
    public ApiResponse<PageResult<CampaignDtos.AdminDetail>> page(
            @RequestParam(defaultValue = "0") long afterId,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(service.adminPage(new PageQuery(afterId, size)));
    }
}
