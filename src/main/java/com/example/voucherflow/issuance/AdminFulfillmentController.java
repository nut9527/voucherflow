package com.example.voucherflow.issuance;
import com.example.voucherflow.common.ApiResponse;
import org.springframework.web.bind.annotation.*;
/** 提供管理员按订单查询履约详情的接口。 */
@RestController
@RequestMapping("/api/admin/fulfillments")
public class AdminFulfillmentController {
    private final FulfillmentQueryService service;
    public AdminFulfillmentController(FulfillmentQueryService service) { this.service = service; }
    @GetMapping("/{id}") public ApiResponse<FulfillmentDtos.OrderDetail> detail(@PathVariable long id) { return ApiResponse.ok(service.adminDetail(id)); }
}
