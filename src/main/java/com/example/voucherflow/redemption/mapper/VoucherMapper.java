package com.example.voucherflow.redemption.mapper;
import com.example.voucherflow.redemption.model.VoucherRow;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
/** 映射券的发放、归属查询、行锁和有效期内核销。 */
@Mapper
public interface VoucherMapper {
    void insert(VoucherRow row);
    Optional<VoucherRow> lockById(long id);
    Optional<VoucherRow> findById(long id);
    Optional<VoucherRow> findByOrderId(long orderId);
    Optional<VoucherRow> findOwned(@Param("id") long id, @Param("userId") long userId);
    List<VoucherRow> findOwnedPage(@Param("userId") long userId, @Param("afterId") long afterId, @Param("limit") int limit);
    int markUsedIfValid(long id);
    Instant databaseNow();
}
