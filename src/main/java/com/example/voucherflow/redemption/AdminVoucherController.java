package com.example.voucherflow.redemption;

import com.example.voucherflow.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 提供管理员查询任意券详情的接口。 */
@RestController
@RequestMapping("/api/admin/vouchers")
public class AdminVoucherController {
    private final VoucherQueryService service;

    public AdminVoucherController(VoucherQueryService service) {
        this.service = service;
    }

    @GetMapping("/{id}")
    public ApiResponse<VoucherDtos.Detail> detail(@PathVariable long id) {
        return ApiResponse.ok(service.adminDetail(id));
    }
}
