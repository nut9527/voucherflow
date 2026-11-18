package com.example.voucherflow.redemption;

import com.example.voucherflow.common.ApiResponse;
import com.example.voucherflow.common.PageQuery;
import com.example.voucherflow.common.PageResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 提供当前用户券包的分页查询与单券详情接口。 */
@RestController
@RequestMapping("/api/me/vouchers")
public class VoucherQueryController {
    private final VoucherQueryService service;

    public VoucherQueryController(VoucherQueryService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<PageResult<VoucherDtos.Detail>> page(
            @RequestParam(defaultValue = "0") long afterId,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(service.ownedPage(new PageQuery(afterId, size)));
    }

    @GetMapping("/{id}")
    public ApiResponse<VoucherDtos.Detail> detail(@PathVariable long id) {
        return ApiResponse.ok(service.ownedDetail(id));
    }
}
