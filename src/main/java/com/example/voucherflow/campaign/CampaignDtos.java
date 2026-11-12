package com.example.voucherflow.campaign;

import com.example.voucherflow.campaign.model.CampaignPhase;
import com.example.voucherflow.campaign.model.CampaignStatus;
import com.example.voucherflow.campaign.model.FulfillmentMode;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;

/** 定义活动接口的请求与响应，区分公开信息和管理员库存明细。 */
public final class CampaignDtos {
    private CampaignDtos() {}

    public record CreateRequest(
            @NotBlank @Size(max = 128) String name,
            @Size(max = 2000) String description,
            @Min(1) @Max(1_000_000_000) int totalStock,
            @NotNull Instant startAt, @NotNull Instant endAt, @NotNull Instant redeemEndAt,
            @NotNull FulfillmentMode fulfillmentMode,
            @Size(max = 64) String supplierId, @Size(max = 128) String productCode,
            @Size(max = 64) String contractVersion) {}

    public record UpdateRequest(
            @NotBlank @Size(max = 128) String name,
            @Size(max = 2000) String description,
            @Min(1) @Max(1_000_000_000) int totalStock,
            @NotNull Instant startAt, @NotNull Instant endAt, @NotNull Instant redeemEndAt,
            @NotNull FulfillmentMode fulfillmentMode,
            @Size(max = 64) String supplierId, @Size(max = 128) String productCode,
            @Size(max = 64) String contractVersion) {}

    public record Created(String id) {}

    public record Summary(String id, String name, int totalStock, CampaignStatus status,
            CampaignPhase phase, Instant startAt, Instant endAt) {}

    public record Detail(String id, String name, int totalStock, CampaignStatus status,
            CampaignPhase phase, Instant startAt, Instant endAt, String description,
            Instant redeemEndAt, FulfillmentMode fulfillmentMode) {}

    public record AdminDetail(String id, String name, String description, int totalStock,
            int availableStock, int reservedStock, int issuedStock, CampaignStatus status,
            Instant startAt, Instant endAt, Instant redeemEndAt, FulfillmentMode fulfillmentMode,
            String supplierId, String productCode, String contractVersion, String createdBy,
            Instant createdAt, Instant updatedAt) {}
}

