package com.example.voucherflow.issuance;
import com.example.voucherflow.common.*;
import org.springframework.web.bind.annotation.*;
/** 提供当前用户的订单列表、订单详情和按活动查询领取结果的接口。 */
@RestController
@RequestMapping("/api/me/fulfillments")
public class FulfillmentQueryController {
    private final FulfillmentQueryService service;
    public FulfillmentQueryController(FulfillmentQueryService service) { this.service = service; }
    @GetMapping public ApiResponse<PageResult<FulfillmentDtos.OrderDetail>> page(
            @RequestParam(defaultValue="0") long afterId, @RequestParam(defaultValue="20") int size) {
        return ApiResponse.ok(service.ownedPage(new PageQuery(afterId,size)));
    }
    @GetMapping("/{id}") public ApiResponse<FulfillmentDtos.OrderDetail> detail(@PathVariable long id) { return ApiResponse.ok(service.ownedDetail(id)); }
    @GetMapping("/by-campaign/{campaignId}") public ApiResponse<FulfillmentDtos.OrderDetail> byCampaign(@PathVariable long campaignId) {
        return ApiResponse.ok(service.ownedByCampaign(campaignId));
    }
}
