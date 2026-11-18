package com.example.voucherflow.redemption.mapper;

import com.example.voucherflow.redemption.model.RedemptionRow;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;

/** 写入和查询券的唯一核销回执。 */
@Mapper
public interface RedemptionMapper {
    void insert(RedemptionRow row);

    Optional<RedemptionRow> findByVoucherId(long voucherId);
}
