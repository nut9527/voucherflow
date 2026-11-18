package com.example.voucherflow.issuance;

import org.springframework.stereotype.Service;

import com.example.voucherflow.common.BusinessException;
import com.example.voucherflow.common.ErrorCode;
import com.example.voucherflow.common.PageQuery;
import com.example.voucherflow.common.PageResult;
import com.example.voucherflow.identity.CurrentUser;
import com.example.voucherflow.issuance.mapper.OrderMapper;
import com.example.voucherflow.issuance.model.OrderView;

/** 查询履约订单视图，普通用户查询限定归属，管理员查询校验角色。 */
@Service
public class FulfillmentQueryService {
    private final OrderMapper orders;
    private final CurrentUser currentUser;

    public FulfillmentQueryService(OrderMapper orders, CurrentUser currentUser) {
        this.orders = orders;
        this.currentUser = currentUser;
    }

    public FulfillmentDtos.OrderDetail ownedDetail(long id) {
        return detail(
                orders.findOwned(id, currentUser.requireUserId())
                        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND)));
    }

    public FulfillmentDtos.OrderDetail ownedByCampaign(long id) {
        return detail(
                orders.findByCampaignAndUser(id, currentUser.requireUserId())
                        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND)));
    }

    public PageResult<FulfillmentDtos.OrderDetail> ownedPage(PageQuery page) {
        var rows = orders
                .findOwnedPage(currentUser.requireUserId(), page.afterId(), page.size() + 1)
                .stream()
                .map(this::detail)
                .toList();
        return PageResult.of(rows, page.size(), FulfillmentDtos.OrderDetail::orderId);
    }

    public FulfillmentDtos.OrderDetail adminDetail(long id) {
        currentUser.requireAdminId();
        return detail(
                orders.findById(id).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND)));
    }

    private FulfillmentDtos.OrderDetail detail(OrderView row) {
        return new FulfillmentDtos.OrderDetail(
                Long.toString(row.orderId()),
                Long.toString(row.campaignId()),
                row.campaignName(),
                row.status(),
                row.voucherId() == null ? null : row.voucherId().toString(),
                row.expiresAt(),
                row.createdAt(),
                row.completedAt());
    }
}
