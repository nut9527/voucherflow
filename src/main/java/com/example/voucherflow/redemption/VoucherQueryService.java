package com.example.voucherflow.redemption;
import com.example.voucherflow.common.*;
import com.example.voucherflow.identity.CurrentUser;
import com.example.voucherflow.redemption.mapper.VoucherMapper;
import com.example.voucherflow.redemption.model.VoucherRow;
import com.example.voucherflow.redemption.model.VoucherStatus;
import java.time.Clock;
import org.springframework.stereotype.Service;
/** 查询用户或管理员可见的券，并按当前时间计算过期展示状态。 */
@Service
public class VoucherQueryService {
    private final VoucherMapper vouchers;
    private final CurrentUser currentUser;
    private final Clock clock;
    public VoucherQueryService(VoucherMapper vouchers, CurrentUser currentUser, Clock clock) {
        this.vouchers = vouchers; this.currentUser = currentUser; this.clock = clock;
    }
    public VoucherDtos.Detail ownedDetail(long id) {
        return detail(vouchers.findOwned(id,currentUser.requireUserId()).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND)));
    }
    public PageResult<VoucherDtos.Detail> ownedPage(PageQuery page) {
        var rows = vouchers.findOwnedPage(currentUser.requireUserId(),page.afterId(),page.size()+1).stream().map(this::detail).toList();
        return PageResult.of(rows,page.size(),VoucherDtos.Detail::id);
    }
    public VoucherDtos.Detail adminDetail(long id) {
        currentUser.requireAdminId();
        return detail(vouchers.findById(id).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND)));
    }
    private VoucherDtos.Detail detail(VoucherRow row) {
        // 已核销优先于过期展示；未使用券无需定时改库即可展示过期状态。
        String state = row.getStatus() == VoucherStatus.USED ? "USED" : !clock.instant().isBefore(row.getExpiresAt()) ? "EXPIRED" : "UNUSED";
        return new VoucherDtos.Detail(row.getId().toString(),Long.toString(row.getOrderId()),Long.toString(row.getCampaignId()),
                row.getCampaignName(),state,row.getIssuedAt(),row.getExpiresAt(),row.getUsedAt());
    }
}
