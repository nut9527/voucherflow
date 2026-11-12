package com.example.vfsupplier;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
/** 定义发放请求、查询结果与带事件标识的回调报文。 */
public final class SupplierDtos {
    private SupplierDtos() {}
    /** 请求号是幂等键；同一请求号必须保持商品与领取主体一致。 */
    public record IssueRequest(@Pattern(regexp="[0-9a-fA-F-]{36}") @NotBlank String requestNo,
                               @NotBlank @Size(max=128) String productCode,@NotBlank @Size(max=128) String subjectRef) {}
    /** 结果版本随供应商状态变化递增，券标识在发放成功后返回。 */
    public record Result(String requestNo,String productCode,String subjectRef,String status,Long resultVersion,String voucherRef) {}
    /** 事件标识供接收方去重，结果版本供接收方判断状态先后。 */
    public record Callback(String eventId,String requestNo,String productCode,String subjectRef,String status,Long resultVersion,String voucherRef) {}
}
