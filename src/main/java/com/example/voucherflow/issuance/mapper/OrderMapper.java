package com.example.voucherflow.issuance.mapper;

import com.example.voucherflow.issuance.model.OrderRow;
import com.example.voucherflow.issuance.model.OrderView;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 映射订单写入、归属查询和履约状态变更，查询视图同时关联活动与券。 */
@Mapper
public interface OrderMapper {
    void insert(OrderRow row);

    Optional<OrderRow> lockById(long id);

    Optional<OrderView> findByCampaignAndUser(
            @Param("campaignId") long campaignId, @Param("userId") long userId);

    Optional<OrderView> findById(long id);

    Optional<OrderView> findOwned(@Param("id") long id, @Param("userId") long userId);

    List<OrderView> findOwnedPage(
            @Param("userId") long userId,
            @Param("afterId") long afterId,
            @Param("limit") int limit);

    int markFulfilled(long id);
}
