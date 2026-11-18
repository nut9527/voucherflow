package com.example.voucherflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.voucherflow.campaign.CampaignDtos;
import com.example.voucherflow.campaign.CampaignPolicy;
import com.example.voucherflow.campaign.model.CampaignPhase;
import com.example.voucherflow.campaign.model.CampaignRow;
import com.example.voucherflow.campaign.model.CampaignStatus;
import com.example.voucherflow.campaign.model.FulfillmentMode;
import com.example.voucherflow.common.BusinessException;
import com.example.voucherflow.common.ErrorCode;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** 验证活动时间窗口、数据库时间精度和履约配置的业务规则。 */
class CampaignPolicyTest {
    private final CampaignPolicy policy = new CampaignPolicy();
    private final Instant start = Instant.parse("2030-01-01T00:00:00Z");
    private final Instant end = start.plusSeconds(3600);

    @Test
    void rejectsEmptyClaimWindow() {
        assertInvalid(
                new CampaignDtos.DraftRequest(
                        "活动", "", 10, start, start, end, FulfillmentMode.LOCAL, null, null, null));
    }

    @Test
    void rejectsWindowCollapsedByDatabaseMillisecondPrecision() {
        assertInvalid(
                new CampaignDtos.DraftRequest(
                        "活动",
                        "",
                        10,
                        start,
                        start.plusNanos(1),
                        end,
                        FulfillmentMode.LOCAL,
                        null,
                        null,
                        null));
    }

    @Test
    void acceptsEqualClaimAndRedemptionDeadlines() {
        policy.validateDraft(
                new CampaignDtos.DraftRequest(
                        "活动", "", 10, start, end, end, FulfillmentMode.LOCAL, null, null, null));
    }

    @Test
    void rejectsRedemptionDeadlineBeforeClaimEnd() {
        assertInvalid(
                new CampaignDtos.DraftRequest(
                        "活动",
                        "",
                        10,
                        start,
                        end,
                        end.minusMillis(1),
                        FulfillmentMode.LOCAL,
                        null,
                        null,
                        null));
    }

    @Test
    void localModeRejectsSupplierFields() {
        assertInvalid(
                new CampaignDtos.DraftRequest(
                        "活动",
                        "",
                        10,
                        start,
                        end,
                        end,
                        FulfillmentMode.LOCAL,
                        "supplier",
                        null,
                        null));
    }

    @Test
    void supplierModeRequiresAllConfiguration() {
        assertInvalid(
                new CampaignDtos.DraftRequest(
                        "活动",
                        "",
                        10,
                        start,
                        end,
                        end,
                        FulfillmentMode.SUPPLIER,
                        "supplier",
                        "product",
                        null));
    }

    @Test
    void supplierCannotPublishWithoutCapability() {
        CampaignRow row = row();
        row.setStatus(CampaignStatus.DRAFT);
        row.setFulfillmentMode(FulfillmentMode.SUPPLIER);
        row.setSupplierId("supplier");
        row.setProductCode("product");
        row.setContractVersion("v1");
        assertThatThrownBy(() -> policy.validatePublish(row, false))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        ex -> assertThat(ex.code()).isEqualTo(ErrorCode.INVALID_CAMPAIGN_STATE));
    }

    @Test
    void phaseUsesInclusiveStartAndExclusiveEnd() {
        CampaignRow row = row();
        assertThat(policy.phase(row, start.minusMillis(1))).isEqualTo(CampaignPhase.NOT_STARTED);
        assertThat(policy.phase(row, start)).isEqualTo(CampaignPhase.CLAIMABLE);
        assertThat(policy.phase(row, end.minusMillis(1))).isEqualTo(CampaignPhase.CLAIMABLE);
        assertThat(policy.phase(row, end)).isEqualTo(CampaignPhase.ENDED);
        row.setStatus(CampaignStatus.DISABLED);
        assertThat(policy.phase(row, start)).isEqualTo(CampaignPhase.DISABLED);
    }

    private CampaignRow row() {
        CampaignRow row = new CampaignRow();
        row.setName("活动");
        row.setTotalStock(10);
        row.setStartAt(start);
        row.setEndAt(end);
        row.setRedeemEndAt(end);
        row.setStatus(CampaignStatus.ACTIVE);
        row.setFulfillmentMode(FulfillmentMode.LOCAL);
        return row;
    }

    private void assertInvalid(CampaignDtos.DraftRequest request) {
        assertThatThrownBy(() -> policy.validateDraft(request))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        ex -> assertThat(ex.code()).isEqualTo(ErrorCode.VALIDATION_ERROR));
    }
}
