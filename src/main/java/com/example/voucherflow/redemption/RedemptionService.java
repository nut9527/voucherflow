package com.example.voucherflow.redemption;
import com.example.voucherflow.common.BusinessException;
import com.example.voucherflow.common.ErrorCode;
import com.example.voucherflow.identity.CurrentUser;
import com.example.voucherflow.redemption.mapper.RedemptionMapper;
import com.example.voucherflow.redemption.mapper.VoucherMapper;
import com.example.voucherflow.redemption.model.RedemptionRow;
import com.example.voucherflow.redemption.model.VoucherStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
/** 在同一事务内核销券并生成回执，重复核销返回首次回执。 */
@Service
public class RedemptionService {
    private final CurrentUser currentUser;
    private final VoucherMapper vouchers;
    private final RedemptionMapper redemptions;
    private final TransactionTemplate tx;
    public RedemptionService(CurrentUser currentUser, VoucherMapper vouchers, RedemptionMapper redemptions, TransactionTemplate tx) {
        this.currentUser = currentUser; this.vouchers = vouchers; this.redemptions = redemptions; this.tx = tx;
    }
    /** 锁定券后核销，券状态与首次回执在同一事务提交。 */
    public VoucherDtos.RedemptionReceipt redeem(long voucherId) {
        long adminId = currentUser.requireAdminId();
        return tx.execute(status -> {
            var voucher = vouchers.lockById(voucherId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
            if (voucher.getStatus() == VoucherStatus.USED) {
                // 重复请求保留首次操作人和时间，不生成新的核销记录。
                return receipt(redemptions.findByVoucherId(voucherId).orElseThrow(() -> new IllegalStateException("Used voucher has no receipt")), true);
            }
            if (vouchers.markUsedIfValid(voucherId) != 1) {
                // 与条件更新一样使用数据库时间，确保有效期边界的判断口径一致。
                if (!vouchers.databaseNow().isBefore(voucher.getExpiresAt())) { throw new BusinessException(ErrorCode.VOUCHER_EXPIRED); }
                throw new BusinessException(ErrorCode.SYSTEM_BUSY);
            }
            var record = new RedemptionRow();
            record.setVoucherId(voucherId); record.setOperatorId(adminId);
            redemptions.insert(record);
            return receipt(redemptions.findByVoucherId(voucherId).orElseThrow(),false);
        });
    }
    private VoucherDtos.RedemptionReceipt receipt(RedemptionRow row, boolean replayed) {
        return new VoucherDtos.RedemptionReceipt(row.getId().toString(),Long.toString(row.getVoucherId()),
                Long.toString(row.getOperatorId()),row.getCreatedAt(),replayed);
    }
}
