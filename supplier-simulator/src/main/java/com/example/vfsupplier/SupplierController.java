package com.example.vfsupplier;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

/** 暴露供应商发放与查单接口，并在事务提交后注入响应丢失故障。 */
@RestController
public class SupplierController {
    private final SupplierIssueService service;
    private final DemoFaults faults;

    public SupplierController(SupplierIssueService service, DemoFaults faults) {
        this.service = service;
        this.faults = faults;
    }

    @PostMapping("/supplier/fulfillments")
    public SupplierDtos.Result issue(@Valid @RequestBody SupplierDtos.IssueRequest request) {
        var result = service.issue(request);
        faults.afterCommit();
        return result;
    }

    @GetMapping("/supplier/fulfillments/{requestNo}")
    public SupplierDtos.Result query(@PathVariable String requestNo) {
        return service.query(requestNo);
    }
}
