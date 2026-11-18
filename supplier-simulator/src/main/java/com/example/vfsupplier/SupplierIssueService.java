package com.example.vfsupplier;

import java.util.List;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

/** 按请求号幂等发放权益，并在状态变更的同一事务中登记回调。 */
@Service
public class SupplierIssueService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper json;

    public SupplierIssueService(JdbcTemplate jdbc, TransactionTemplate tx, ObjectMapper json) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.json = json;
    }

    /** 先持久化受理事实，再完成发放；重复请求复用原有结果。 */
    public SupplierDtos.Result issue(SupplierDtos.IssueRequest request) {
        String hash = Protocol.fingerprint(request.productCode(), request.subjectRef());
        try {
            tx.executeWithoutResult(
                    status -> {
                        jdbc.update(
                                "INSERT INTO supplier_requests(request_no,fingerprint,product_code,subject_ref,status,result_version,idempotency_deadline_at) VALUES(?,?,?,?,'ACCEPTED',1,TIMESTAMPADD(HOUR,24,UTC_TIMESTAMP(3)))",
                                request.requestNo(),
                                hash,
                                request.productCode(),
                                request.subjectRef());
                        appendCallback(query(request.requestNo()));
                    });
        } catch (DuplicateKeyException ex) {
            // 请求号已存在时核对参数指纹，避免将不同业务请求当成一次重试。
            String previous =
                    jdbc.queryForObject(
                            "SELECT fingerprint FROM supplier_requests WHERE request_no=?",
                            String.class,
                            request.requestNo());
            if (!hash.equals(previous))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Request parameters differ");
        }
        return complete(request.requestNo());
    }

    /** 未查到记录返回 NOT_FOUND，与已明确拒绝的 REJECTED 状态区分。 */
    public SupplierDtos.Result query(String requestNo) {
        var rows =
                jdbc.query(
                        "SELECT r.*,v.voucher_ref FROM supplier_requests r LEFT JOIN supplier_vouchers v ON v.request_no=r.request_no WHERE r.request_no=?",
                        (r, n) ->
                                new SupplierDtos.Result(
                                        r.getString("request_no"),
                                        r.getString("product_code"),
                                        r.getString("subject_ref"),
                                        r.getString("status"),
                                        r.getLong("result_version"),
                                        r.getString("voucher_ref")),
                        requestNo);
        return rows.isEmpty()
                ? new SupplierDtos.Result(requestNo, null, null, "NOT_FOUND", null, null)
                : rows.getFirst();
    }

    /** 对请求加行锁，使即时发放、定时恢复与并发重试共用一次终态提交。 */
    private SupplierDtos.Result complete(String requestNo) {
        return tx.execute(
                status -> {
                    String current =
                            jdbc.queryForObject(
                                    "SELECT status FROM supplier_requests WHERE request_no=? FOR UPDATE",
                                    String.class,
                                    requestNo);
                    if (!"ACCEPTED".equals(current)) return query(requestNo);
                    var request = query(requestNo);
                    // 商品编码 reject 是模拟明确拒绝的演示约定。
                    boolean rejected = "reject".equals(request.productCode());
                    if (!rejected)
                        jdbc.update(
                                "INSERT INTO supplier_vouchers(voucher_ref,request_no) VALUES(?,?)",
                                UUID.randomUUID().toString(),
                                requestNo);
                    jdbc.update(
                            "UPDATE supplier_requests SET status=?,result_version=2,updated_at=UTC_TIMESTAMP(3) WHERE request_no=?",
                            rejected ? "REJECTED" : "SUCCEEDED",
                            requestNo);
                    var result = query(requestNo);
                    appendCallback(result);
                    return result;
                });
    }

    /** 在当前事务中固化回调报文，使回调与对应结果版本一同提交。 */
    private void appendCallback(SupplierDtos.Result result) {
        String id = UUID.randomUUID().toString();
        var event =
                new SupplierDtos.Callback(
                        id,
                        result.requestNo(),
                        result.productCode(),
                        result.subjectRef(),
                        result.status(),
                        result.resultVersion(),
                        result.voucherRef());
        jdbc.update(
                "INSERT INTO callback_jobs(event_id,request_no,result_version,payload) VALUES(?,?,?,?)",
                id,
                result.requestNo(),
                result.resultVersion(),
                json.writeValueAsString(event));
    }

    /** 接续已受理但尚未完成的请求，覆盖受理提交后进程中断的场景。 */
    @Scheduled(fixedDelay = 1000)
    public void completeAccepted() {
        List<String> requests =
                jdbc.queryForList(
                        "SELECT request_no FROM supplier_requests WHERE status='ACCEPTED' ORDER BY created_at LIMIT 16",
                        String.class);
        for (String request : requests) complete(request);
    }
}
