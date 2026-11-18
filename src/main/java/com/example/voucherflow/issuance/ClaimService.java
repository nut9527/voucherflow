package com.example.voucherflow.issuance;

import java.util.UUID;

import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.voucherflow.campaign.mapper.CampaignMapper;
import com.example.voucherflow.campaign.model.FulfillmentMode;
import com.example.voucherflow.common.BusinessException;
import com.example.voucherflow.common.ErrorCode;
import com.example.voucherflow.fulfillment.FulfillmentEventFactory;
import com.example.voucherflow.fulfillment.FulfillmentFinalizer;
import com.example.voucherflow.fulfillment.FulfillmentProperties;
import com.example.voucherflow.fulfillment.SupplierStore;
import com.example.voucherflow.fulfillment.supplier.SupplierProtocol;
import com.example.voucherflow.identity.CurrentUser;
import com.example.voucherflow.inventory.InventoryService;
import com.example.voucherflow.inventory.ReservationNotAcquiredException;
import com.example.voucherflow.issuance.mapper.OrderMapper;
import com.example.voucherflow.issuance.model.FulfillmentStatus;
import com.example.voucherflow.issuance.model.OrderRow;
import com.example.voucherflow.issuance.model.OrderView;
import com.example.voucherflow.outbox.MessagingProperties;
import com.example.voucherflow.outbox.OutboxEventService;

/** 编排领取、库存预留和履约启动，同一活动下同一用户始终复用已有订单。 */
@Service
public class ClaimService {
    private final CurrentUser currentUser;
    private final CampaignMapper campaigns;
    private final OrderMapper orders;
    private final InventoryService inventory;
    private final FulfillmentFinalizer finalizer;
    private final TransactionTemplate tx;
    private final FulfillmentProperties fulfillment;
    private final FulfillmentEventFactory events;
    private final OutboxEventService outbox;
    private final SupplierStore supplier;
    private final MessagingProperties messaging;

    public ClaimService(
            CurrentUser currentUser,
            CampaignMapper campaigns,
            OrderMapper orders,
            InventoryService inventory,
            FulfillmentFinalizer finalizer,
            TransactionTemplate tx,
            FulfillmentProperties fulfillment,
            FulfillmentEventFactory events,
            OutboxEventService outbox,
            SupplierStore supplier,
            MessagingProperties messaging) {
        this.currentUser = currentUser;
        this.campaigns = campaigns;
        this.orders = orders;
        this.inventory = inventory;
        this.finalizer = finalizer;
        this.tx = tx;
        this.fulfillment = fulfillment;
        this.events = events;
        this.outbox = outbox;
        this.supplier = supplier;
        this.messaging = messaging;
    }

    /** 先回查已有领取结果，新订单在事务中创建。 */
    public FulfillmentDtos.ClaimResult claim(long campaignId) {
        long userId = currentUser.requireUserId();
        // 已受理的领取优先返回原结果，活动结束、库存售罄或暂停接单都不影响幂等查询。
        var existing = orders.findByCampaignAndUser(campaignId, userId);
        if (existing.isPresent()) {
            return replay(existing.get());
        }
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                return tx.execute(status -> create(campaignId, userId));
            } catch (ReservationNotAcquiredException ex) {
                return orders.findByCampaignAndUser(campaignId, userId)
                        .map(this::replay)
                        .orElseThrow(
                                () -> new BusinessException(inventory.reservationFailure(campaignId)));
            } catch (DataAccessResourceFailureException | TransactionSystemException ex) {
                // 连接或提交失败时事务可能已经落库，先回查而不直接判为领取失败。
                return replayUnknown(campaignId, userId);
            } catch (DuplicateKeyException ex) {
                // 唯一约束覆盖并发重复领取，冲突请求复用已提交订单。
                return orders.findByCampaignAndUser(campaignId, userId)
                        .map(this::replay)
                        .orElseThrow(() -> ex);
            } catch (PessimisticLockingFailureException ex) {
                // 锁冲突后重新开启一次事务，避免在已失败的事务中继续执行。
                if (attempt == 0) {
                    continue;
                }
                throw new BusinessException(ErrorCode.SYSTEM_BUSY);
            }
        }
        throw new BusinessException(ErrorCode.SYSTEM_BUSY);
    }

    private FulfillmentDtos.ClaimResult create(long campaignId, long userId) {
        // 活动行锁串行化库存变更，取得锁后再次检查并发请求是否已经创建订单。
        var campaign = campaigns
                .lockById(campaignId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        var existing = orders.findByCampaignAndUser(campaignId, userId);
        if (existing.isPresent()) {
            return replay(existing.get());
        }
        if (campaign.getFulfillmentMode() == FulfillmentMode.SUPPLIER
                && (!fulfillment.isEnabled()
                        || !messaging.enabled()
                        || !messaging.publishingEnabled()
                        || !fulfillment.isAcceptingNew()
                        || campaign.getReservedStock() >= fulfillment.getMaxReservedPerCampaign()
                        || !fulfillment.getSupplierId().equals(campaign.getSupplierId())
                        || !fulfillment
                                .getContractVersion()
                                .equals(campaign.getContractVersion()))) {
            throw new BusinessException(ErrorCode.SYSTEM_BUSY);
        }
        inventory.reserveInCurrentTransaction(campaignId);
        var order = new OrderRow();
        order.setRequestNo(UUID.randomUUID().toString());
        order.setCampaignId(campaignId);
        order.setUserId(userId);
        order.setFulfillmentMode(campaign.getFulfillmentMode());
        order.setExpiresAt(campaign.getRedeemEndAt());
        if (campaign.getFulfillmentMode() == FulfillmentMode.SUPPLIER) {
            order.setSupplierId(campaign.getSupplierId());
            order.setProductCode(campaign.getProductCode());
            order.setContractVersion(campaign.getContractVersion());
            order.setSubjectRef("user:" + userId);
            order.setRequestFingerprint(
                    SupplierProtocol.fingerprint(order.getProductCode(), order.getSubjectRef()));
            // 本地截止时间预留十秒余量，减少贴近供应商幂等窗口边界时重发的风险。
            order.setIdempotencyDeadlineAt(
                    supplier.now().plusSeconds(fulfillment.getIdempotencySeconds() - 10));
        }
        orders.insert(order);
        inventory.appendReserveInCurrentTransaction(campaignId, order.getId());
        if (campaign.getFulfillmentMode() == FulfillmentMode.SUPPLIER) {
            // 订单、预留流水与履约请求事件共同提交，后续由消息链路登记持久任务。
            outbox.appendInCurrentTransaction(events.fulfillmentRequested(order, supplier.now()));
            return new FulfillmentDtos.ClaimResult(
                    order.getId().toString(),
                    Long.toString(campaignId),
                    FulfillmentStatus.PENDING,
                    null,
                    false);
        }
        // 本地模式在领取事务中直接确认库存并生成券。
        var result = finalizer.confirmLocalInCurrentTransaction(campaignId, order.getId());
        return new FulfillmentDtos.ClaimResult(
                Long.toString(result.orderId()),
                Long.toString(campaignId),
                result.status(),
                Long.toString(result.voucherId()),
                result.replayed());
    }

    private FulfillmentDtos.ClaimResult replay(OrderView row) {
        return new FulfillmentDtos.ClaimResult(
                Long.toString(row.orderId()),
                Long.toString(row.campaignId()),
                row.status(),
                row.voucherId() == null ? null : row.voucherId().toString(),
                true);
    }

    private FulfillmentDtos.ClaimResult replayUnknown(long campaignId, long userId) {
        try {
            return orders.findByCampaignAndUser(campaignId, userId)
                    .map(this::replay)
                    .orElseThrow(() -> new BusinessException(ErrorCode.RESULT_UNKNOWN));
        } catch (DataAccessException ex) {
            throw new BusinessException(ErrorCode.RESULT_UNKNOWN);
        }
    }
}
