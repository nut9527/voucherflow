package com.example.voucherflow;

import com.example.voucherflow.campaign.CampaignPolicy;
import com.example.voucherflow.campaign.CampaignReadService;
import com.example.voucherflow.campaign.CampaignService;
import com.example.voucherflow.campaign.cache.CampaignCacheInvalidator;
import com.example.voucherflow.campaign.mapper.CampaignMapper;
import com.example.voucherflow.campaign.model.CampaignRow;
import com.example.voucherflow.campaign.model.CampaignStatus;
import com.example.voucherflow.campaign.model.FulfillmentMode;
import com.example.voucherflow.common.BusinessException;
import com.example.voucherflow.common.ErrorCode;
import com.example.voucherflow.fulfillment.FulfillmentEventFactory;
import com.example.voucherflow.fulfillment.FulfillmentFinalizer;
import com.example.voucherflow.fulfillment.FulfillmentProperties;
import com.example.voucherflow.fulfillment.SupplierStore;
import com.example.voucherflow.identity.CurrentUser;
import com.example.voucherflow.inventory.InventoryService;
import com.example.voucherflow.inventory.mapper.InventoryMapper;
import com.example.voucherflow.issuance.ClaimService;
import com.example.voucherflow.issuance.mapper.OrderMapper;
import com.example.voucherflow.issuance.model.FulfillmentStatus;
import com.example.voucherflow.issuance.model.OrderView;
import com.example.voucherflow.outbox.MessagingProperties;
import com.example.voucherflow.outbox.OutboxEventService;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 验证停止事件发布后禁止创建新供应商业务，但已存在订单仍可幂等返回。 */
class SupplierAdmissionTest {
    private final CurrentUser currentUser = mock(CurrentUser.class);
    private final CampaignMapper campaigns = mock(CampaignMapper.class);
    private final OrderMapper orders = mock(OrderMapper.class);
    private final InventoryService inventory = mock(InventoryService.class);
    private final OutboxEventService outbox = mock(OutboxEventService.class);
    private final MessagingProperties messaging = mock(MessagingProperties.class);
    private final FulfillmentProperties fulfillment = new FulfillmentProperties();
    private final TransactionTemplate tx = new TransactionTemplate(mock(PlatformTransactionManager.class));
    private final CampaignRow campaign = new CampaignRow();
    private final ClaimService claims = new ClaimService(currentUser, campaigns, orders, inventory,
            mock(InventoryMapper.class), mock(FulfillmentFinalizer.class), tx, fulfillment,
            mock(FulfillmentEventFactory.class), outbox, mock(SupplierStore.class), messaging);

    @BeforeEach void setup() {
        fulfillment.setEnabled(true);
        when(messaging.enabled()).thenReturn(true);
        when(currentUser.requireUserId()).thenReturn(42L);
        campaign.setId(1L);
        campaign.setName("暂停发布");
        campaign.setTotalStock(1);
        campaign.setStatus(CampaignStatus.DRAFT);
        campaign.setStartAt(Instant.EPOCH);
        campaign.setEndAt(Instant.EPOCH.plusSeconds(3600));
        campaign.setRedeemEndAt(Instant.EPOCH.plusSeconds(7200));
        campaign.setFulfillmentMode(FulfillmentMode.SUPPLIER);
        campaign.setSupplierId("simulator");
        campaign.setProductCode("course");
        campaign.setContractVersion("v1");
        when(campaigns.lockById(1)).thenReturn(Optional.of(campaign));
    }

    @Test void pausedPublisherRejectsCampaignPublication() {
        var service = new CampaignService(campaigns, new CampaignPolicy(), currentUser, Clock.systemUTC(),
                tx, mock(CampaignReadService.class), mock(CampaignCacheInvalidator.class), fulfillment, messaging);
        assertThatThrownBy(() -> service.publish(1)).isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.code()).isEqualTo(ErrorCode.INVALID_CAMPAIGN_STATE));
        verify(campaigns, never()).publishDraft(anyLong());
    }

    @Test void pausedPublisherRejectsClaimBeforeReservingStock() {
        campaign.setStatus(CampaignStatus.ACTIVE);
        assertThatThrownBy(() -> claims.claim(1)).isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.code()).isEqualTo(ErrorCode.SYSTEM_BUSY));
        verifyNoInteractions(inventory, outbox);
        verify(orders, never()).insert(any());
    }

    @Test void pausedPublisherStillReplaysExistingOrder() {
        when(orders.findByCampaignAndUser(1, 42)).thenReturn(Optional.of(new OrderView(
                7, 1, 42, "暂停发布", FulfillmentStatus.PENDING, null, Instant.MAX, Instant.EPOCH, null)));
        var replay = claims.claim(1);
        assertThat(replay.orderId()).isEqualTo("7");
        assertThat(replay.replayed()).isTrue();
        verifyNoInteractions(inventory, outbox);
    }
}
