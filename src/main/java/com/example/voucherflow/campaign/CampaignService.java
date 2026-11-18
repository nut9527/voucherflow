package com.example.voucherflow.campaign;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.voucherflow.campaign.cache.CampaignCacheInvalidator;
import com.example.voucherflow.campaign.mapper.CampaignMapper;
import com.example.voucherflow.campaign.model.CampaignRow;
import com.example.voucherflow.campaign.model.CampaignStatus;
import com.example.voucherflow.campaign.model.FulfillmentMode;
import com.example.voucherflow.common.BusinessException;
import com.example.voucherflow.common.ErrorCode;
import com.example.voucherflow.common.PageQuery;
import com.example.voucherflow.common.PageResult;
import com.example.voucherflow.fulfillment.FulfillmentProperties;
import com.example.voucherflow.identity.CurrentUser;
import com.example.voucherflow.outbox.MessagingProperties;

/** 管理活动草稿、发布与停用，在事务提交后使详情缓存失效。 */
@Service
public class CampaignService {
    private final CampaignMapper mapper;
    private final CampaignPolicy policy;
    private final CurrentUser currentUser;
    private final Clock clock;
    private final TransactionTemplate transaction;
    private final CampaignReadService reads;
    private final CampaignCacheInvalidator invalidator;
    private final FulfillmentProperties fulfillment;
    private final MessagingProperties messaging;

    public CampaignService(
            CampaignMapper mapper,
            CampaignPolicy policy,
            CurrentUser currentUser,
            Clock clock,
            TransactionTemplate transaction,
            CampaignReadService reads,
            CampaignCacheInvalidator invalidator,
            FulfillmentProperties fulfillment,
            MessagingProperties messaging) {
        this.mapper = mapper;
        this.policy = policy;
        this.currentUser = currentUser;
        this.clock = clock;
        this.transaction = transaction;
        this.reads = reads;
        this.invalidator = invalidator;
        this.fulfillment = fulfillment;
        this.messaging = messaging;
    }

    public CampaignDtos.Created create(CampaignDtos.DraftRequest request) {
        long adminId = currentUser.requireAdminId();
        policy.validateDraft(request);
        CampaignRow row = new CampaignRow();
        row.setName(request.name().strip());
        row.setDescription(request.description() == null ? "" : request.description());
        row.setTotalStock(request.totalStock());
        row.setAvailableStock(request.totalStock());
        row.setStatus(CampaignStatus.DRAFT);
        row.setStartAt(millis(request.startAt()));
        row.setEndAt(millis(request.endAt()));
        row.setRedeemEndAt(millis(request.redeemEndAt()));
        row.setFulfillmentMode(request.fulfillmentMode());
        row.setSupplierId(request.supplierId());
        row.setProductCode(request.productCode());
        row.setContractVersion(request.contractVersion());
        row.setCreatedBy(adminId);
        return transaction.execute(
                status -> {
                    mapper.insertDraft(row);
                    invalidator.evictAfterCommit(row.getId());
                    return new CampaignDtos.Created(row.getId().toString());
                });
    }

    /** 仅允许修改草稿，并在行锁内完成状态检查与更新。 */
    public CampaignDtos.AdminDetail update(long id, CampaignDtos.DraftRequest request) {
        currentUser.requireAdminId();
        policy.validateDraft(request);
        var params = new CampaignMapper.UpdateDraftParams(
                request.name().strip(),
                request.description() == null ? "" : request.description(),
                request.totalStock(),
                millis(request.startAt()),
                millis(request.endAt()),
                millis(request.redeemEndAt()),
                request.fulfillmentMode(),
                request.supplierId(),
                request.productCode(),
                request.contractVersion());
        return transaction.execute(
                status -> {
                    CampaignRow row = lock(id);
                    requireStatus(row, CampaignStatus.DRAFT);
                    requireOne(mapper.updateDraft(id, params));
                    invalidator.evictAfterCommit(id);
                    return toAdminDetail(find(id));
                });
    }

    /** 发布前确认履约能力与供应商协议配置，重复发布直接返回当前详情。 */
    public CampaignDtos.AdminDetail publish(long id) {
        currentUser.requireAdminId();
        return transaction.execute(
                status -> {
                    CampaignRow row = lock(id);
                    if (row.getStatus() == CampaignStatus.ACTIVE) {
                        return toAdminDetail(row);
                    }
                    policy.validatePublish(
                            row,
                            fulfillment.isEnabled()
                                    && messaging.enabled()
                                    && messaging.publishingEnabled());
                    if (row.getFulfillmentMode() == FulfillmentMode.SUPPLIER
                            && (!fulfillment.getSupplierId().equals(row.getSupplierId())
                                    || !fulfillment
                                            .getContractVersion()
                                            .equals(row.getContractVersion()))) {
                        throw new BusinessException(ErrorCode.INVALID_CAMPAIGN_STATE, "供应商协议未配置");
                    }
                    requireOne(mapper.publishDraft(id));
                    invalidator.evictAfterCommit(id);
                    return toAdminDetail(find(id));
                });
    }

    /** 停止后续领取；重复停用沿用已有状态。 */
    public CampaignDtos.AdminDetail disable(long id) {
        currentUser.requireAdminId();
        return transaction.execute(
                status -> {
                    CampaignRow row = lock(id);
                    if (row.getStatus() == CampaignStatus.DISABLED) {
                        return toAdminDetail(row);
                    }
                    requireStatus(row, CampaignStatus.ACTIVE);
                    requireOne(mapper.disableActive(id));
                    invalidator.evictAfterCommit(id);
                    return toAdminDetail(find(id));
                });
    }

    public CampaignDtos.Detail publicDetail(long id) {
        return reads.detail(id);
    }

    public PageResult<CampaignDtos.Summary> publicPage(PageQuery query) {
        Instant now = clock.instant();
        // 多读一条判断是否还有下一页，同一页使用同一个时间点计算领取阶段。
        var rows = mapper.findPublicPage(query.afterId(), query.size() + 1).stream()
                .map(
                        row -> new CampaignDtos.Summary(
                                row.getId().toString(),
                                row.getName(),
                                row.getTotalStock(),
                                row.getStatus(),
                                policy.phase(row, now),
                                row.getStartAt(),
                                row.getEndAt()))
                .toList();
        return PageResult.of(rows, query.size(), CampaignDtos.Summary::id);
    }

    public CampaignDtos.AdminDetail adminDetail(long id) {
        currentUser.requireAdminId();
        return toAdminDetail(find(id));
    }

    public PageResult<CampaignDtos.AdminDetail> adminPage(PageQuery query) {
        currentUser.requireAdminId();
        var rows = mapper.findAdminPage(query.afterId(), query.size() + 1).stream()
                .map(this::toAdminDetail)
                .toList();
        return PageResult.of(rows, query.size(), CampaignDtos.AdminDetail::id);
    }

    private CampaignRow find(long id) {
        return mapper.findById(id).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    private CampaignRow lock(long id) {
        return mapper.lockById(id).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    private void requireStatus(CampaignRow row, CampaignStatus status) {
        if (row.getStatus() != status) {
            throw new BusinessException(ErrorCode.INVALID_CAMPAIGN_STATE);
        }
    }

    private void requireOne(int affected) {
        if (affected != 1) {
            throw new IllegalStateException("Expected one campaign row to change");
        }
    }

    private Instant millis(Instant value) {
        // 与数据库毫秒精度对齐，避免时间窗口在写入前后发生边界变化。
        return value.truncatedTo(ChronoUnit.MILLIS);
    }

    private CampaignDtos.AdminDetail toAdminDetail(CampaignRow row) {
        return new CampaignDtos.AdminDetail(
                row.getId().toString(),
                row.getName(),
                row.getDescription(),
                row.getTotalStock(),
                row.getAvailableStock(),
                row.getReservedStock(),
                row.getIssuedStock(),
                row.getStatus(),
                row.getStartAt(),
                row.getEndAt(),
                row.getRedeemEndAt(),
                row.getFulfillmentMode(),
                row.getSupplierId(),
                row.getProductCode(),
                row.getContractVersion(),
                Long.toString(row.getCreatedBy()),
                row.getCreatedAt(),
                row.getUpdatedAt());
    }
}
