package com.example.voucherflow.inventory.mapper;

import com.example.voucherflow.inventory.model.LedgerType;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 按订单记录库存流水，供幂等约束与本地对账使用。 */
@Mapper
public interface LedgerMapper {
    void insert(
            @Param("orderId") long orderId,
            @Param("campaignId") long campaignId,
            @Param("type") LedgerType type);
}
