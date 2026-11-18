package com.example.voucherflow.reconciliation;

import com.example.voucherflow.common.ApiResponse;
import com.example.voucherflow.common.PageQuery;
import com.example.voucherflow.common.PageResult;
import com.example.voucherflow.reconciliation.ReconciliationDtos.DiffDetail;
import com.example.voucherflow.reconciliation.ReconciliationDtos.RepairPreview;
import com.example.voucherflow.reconciliation.ReconciliationDtos.RepairReceipt;
import com.example.voucherflow.reconciliation.ReconciliationDtos.RepairRequest;
import com.example.voucherflow.reconciliation.ReconciliationDtos.RunDetail;
import com.example.voucherflow.reconciliation.ReconciliationDtos.RunRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 提供管理员对账任务、差异查询、修复预览与修复执行接口。 */
@RestController
@RequestMapping("/api/admin/reconciliation")
public class ReconciliationController {
    private final ReconciliationService service;
    private final RepairService repairs;

    public ReconciliationController(ReconciliationService service, RepairService repairs) {
        this.service = service;
        this.repairs = repairs;
    }

    @PostMapping("/runs")
    public ResponseEntity<ApiResponse<RunDetail>> create(@Valid @RequestBody RunRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponse.ok(service.create(request)));
    }

    @GetMapping("/runs/{id}")
    public ApiResponse<RunDetail> detail(@PathVariable @Positive long id) {
        return ApiResponse.ok(service.detail(id));
    }

    @GetMapping("/runs/{id}/diffs")
    public ApiResponse<PageResult<DiffDetail>> diffs(
            @PathVariable @Positive long id,
            @RequestParam(defaultValue = "0") long afterId,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(service.diffs(id, new PageQuery(afterId, size)));
    }

    @GetMapping("/diffs/{id}/repair-preview")
    public ApiResponse<RepairPreview> preview(@PathVariable @Positive long id) {
        return ApiResponse.ok(repairs.preview(id));
    }

    @PostMapping("/diffs/{id}/repair")
    public ApiResponse<RepairReceipt> repair(
            @PathVariable @Positive long id, @Valid @RequestBody RepairRequest request) {
        return ApiResponse.ok(repairs.repair(id, request));
    }
}
