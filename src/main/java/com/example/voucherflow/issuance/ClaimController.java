package com.example.voucherflow.issuance;
import com.example.voucherflow.common.ApiResponse;
import com.example.voucherflow.protection.ClaimAdmissionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
/** 在领取准入检查后创建或重放订单，并按履约进度返回 HTTP 状态。 */
@RestController
public class ClaimController {
    private final ClaimService service;
    private final ClaimAdmissionService admission;
    public ClaimController(ClaimService service, ClaimAdmissionService admission) {
        this.service = service; this.admission = admission;
    }
    @PostMapping("/api/campaigns/{id}/claim")
    public ResponseEntity<ApiResponse<FulfillmentDtos.ClaimResult>> claim(@PathVariable long id) {
        admission.check(id);
        var result = service.claim(id);
        // 重复领取返回 200；首次完成返回 201；异步受理但尚未完成返回 202。
        int status=result.replayed()?200:result.fulfillmentStatus().isTerminal()?201:202;
        return ResponseEntity.status(status).body(ApiResponse.ok(result));
    }
}
