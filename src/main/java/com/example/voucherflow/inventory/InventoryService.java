package com.example.voucherflow.inventory;
import com.example.voucherflow.inventory.mapper.InventoryMapper;
import com.example.voucherflow.inventory.mapper.LedgerMapper;
import com.example.voucherflow.inventory.model.LedgerType;
import com.example.voucherflow.issuance.ReservationNotAcquiredException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
/** 在调用方事务中变更库存并记录流水，使库存变动与订单结果共同提交。 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class InventoryService {
    private final InventoryMapper inventory;
    private final LedgerMapper ledger;
    public InventoryService(InventoryMapper inventory, LedgerMapper ledger) { this.inventory = inventory; this.ledger = ledger; }
    /** 先将可用库存转为预留；订单生成后再补写带订单编号的预留流水。 */
    public void reserveInCurrentTransaction(long campaignId) {
        if (inventory.reserve(campaignId) != 1) { throw new ReservationNotAcquiredException(); }
    }
    public void appendReserveInCurrentTransaction(long campaignId, long orderId) {
        ledger.insert(orderId, campaignId, LedgerType.RESERVE);
    }
    /** 将预留转为已发放，并在当前事务中记录确认流水。 */
    public void confirmInCurrentTransaction(long campaignId, long orderId) {
        requireOne(inventory.confirm(campaignId));
        ledger.insert(orderId, campaignId, LedgerType.CONFIRM);
    }
    /** 将预留退回可用库存，并在当前事务中记录释放流水。 */
    public void releaseInCurrentTransaction(long campaignId, long orderId) {
        requireOne(inventory.release(campaignId));
        ledger.insert(orderId, campaignId, LedgerType.RELEASE);
    }
    private void requireOne(int rows) {
        if (rows != 1) { throw new IllegalStateException("Inventory mutation did not affect one row"); }
    }
}
