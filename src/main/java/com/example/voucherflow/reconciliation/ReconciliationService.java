package com.example.voucherflow.reconciliation;

import com.example.voucherflow.common.*;
import com.example.voucherflow.identity.CurrentUser;
import com.example.voucherflow.reconciliation.ReconciliationDtos.*;
import org.springframework.stereotype.Service;

/** 校验管理员身份，提供对账任务创建、详情和差异分页查询。 */
@Service
public class ReconciliationService {
    private final CurrentUser user;
    private final ReconciliationStore store;
    public ReconciliationService(CurrentUser user,ReconciliationStore store) {this.user=user;this.store=store;}
    public RunDetail create(RunRequest request) {user.requireAdminId();return store.run(store.create(request.campaignId()));}
    public RunDetail detail(long id) {user.requireAdminId();return store.run(id);}
    public PageResult<DiffDetail> diffs(long id,PageQuery query) {
        user.requireAdminId(); store.run(id);
        return PageResult.of(store.diffs(id,query.afterId(),query.size()+1),query.size(),DiffDetail::id);
    }
}
