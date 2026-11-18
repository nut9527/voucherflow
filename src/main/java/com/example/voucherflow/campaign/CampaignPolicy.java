package com.example.voucherflow.campaign;

import com.example.voucherflow.campaign.model.CampaignPhase;
import com.example.voucherflow.campaign.model.CampaignRow;
import com.example.voucherflow.campaign.model.CampaignStatus;
import com.example.voucherflow.campaign.model.FulfillmentMode;
import com.example.voucherflow.common.BusinessException;
import com.example.voucherflow.common.ErrorCode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Component;

/** 集中定义活动参数、发布条件和领取阶段的业务规则。 */
@Component
public class CampaignPolicy {
    public void validateDraft(CampaignDtos.DraftRequest request) {
        validateFields(
                request.name(),
                request.description(),
                request.totalStock(),
                request.startAt(),
                request.endAt(),
                request.redeemEndAt(),
                request.fulfillmentMode(),
                request.supplierId(),
                request.productCode(),
                request.contractVersion());
    }

    public void validatePublish(CampaignRow row, boolean supplierEnabled) {
        if (row.getStatus() != CampaignStatus.DRAFT) {
            throw new BusinessException(ErrorCode.INVALID_CAMPAIGN_STATE);
        }
        validateFields(
                row.getName(),
                row.getDescription(),
                row.getTotalStock(),
                row.getStartAt(),
                row.getEndAt(),
                row.getRedeemEndAt(),
                row.getFulfillmentMode(),
                row.getSupplierId(),
                row.getProductCode(),
                row.getContractVersion());
        if (row.getFulfillmentMode() == FulfillmentMode.SUPPLIER && !supplierEnabled) {
            throw new BusinessException(ErrorCode.INVALID_CAMPAIGN_STATE, "供应商履约尚未启用");
        }
    }

    /** 领取窗口包含开始时刻、不包含结束时刻，停用状态优先于时间判断。 */
    public CampaignPhase phase(CampaignRow row, Instant now) {
        if (row.getStatus() == CampaignStatus.DISABLED) {
            return CampaignPhase.DISABLED;
        }
        if (row.getStatus() == CampaignStatus.ENDED || !now.isBefore(row.getEndAt())) {
            return CampaignPhase.ENDED;
        }
        return now.isBefore(row.getStartAt()) ? CampaignPhase.NOT_STARTED : CampaignPhase.CLAIMABLE;
    }

    private void validateFields(
            String name,
            String description,
            int stock,
            Instant start,
            Instant end,
            Instant redeemEnd,
            FulfillmentMode mode,
            String supplier,
            String product,
            String contract) {
        if (name == null
                || name.isBlank()
                || name.length() > 128
                || (description != null && description.length() > 2000)
                || stock < 1
                || stock > 1_000_000_000
                || start == null
                || end == null
                || redeemEnd == null
                || mode == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "活动参数不完整或超出范围");
        }
        // 按实际入库精度比较，确保毫秒截断后领取窗口仍有正长度。
        if (!start.truncatedTo(ChronoUnit.MILLIS).isBefore(end.truncatedTo(ChronoUnit.MILLIS))
                || end.truncatedTo(ChronoUnit.MILLIS)
                        .isAfter(redeemEnd.truncatedTo(ChronoUnit.MILLIS))) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "领取开始时间须早于结束时间，核销截止不得早于领取结束");
        }
        if (mode == FulfillmentMode.LOCAL
                && (supplier != null || product != null || contract != null)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "本地履约不能配置供应商参数");
        }
        if (mode == FulfillmentMode.SUPPLIER
                && (!validText(supplier, 64)
                        || !validText(product, 128)
                        || !validText(contract, 64))) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "供应商履约需要完整的供应商、商品和协议版本");
        }
    }

    private boolean validText(String value, int limit) {
        return value != null && !value.isBlank() && value.length() <= limit;
    }
}
