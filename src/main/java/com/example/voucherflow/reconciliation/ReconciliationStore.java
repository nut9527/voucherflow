package com.example.voucherflow.reconciliation;

import com.example.voucherflow.common.BusinessException;
import com.example.voucherflow.common.ErrorCode;
import com.example.voucherflow.reconciliation.DifferenceClassifier.Observation;
import com.example.voucherflow.reconciliation.ReconciliationDtos.*;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** 持久化对账扫描边界、租约、差异及修复命令回执。 */
@Repository
public class ReconciliationStore {
    public record Accounting(boolean valid, Instant observedAt) {}

    public record Lease(
            long id,
            long campaignId,
            long cursor,
            long upperOrderId,
            String token,
            Accounting accounting) {}

    public record Command(String fingerprint, RepairReceipt receipt) {}

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final ReconciliationProperties properties;

    public ReconciliationStore(
            JdbcTemplate jdbc,
            TransactionTemplate tx,
            ObjectMapper json,
            ReconciliationProperties properties) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.json = json;
        this.properties = properties;
    }

    public Instant now() {
        return jdbc.queryForObject(
                "SELECT UTC_TIMESTAMP(3)",
                (r, n) -> r.getObject(1, LocalDateTime.class).toInstant(ZoneOffset.UTC));
    }

    /** 创建任务时冻结订单编号上界，使扫描不会被持续新增的订单无限延长。 */
    public long create(long campaignId) {
        return tx.execute(
                s -> {
                    // 领取也先锁活动行，此处在同一锁内确定上界；冻结的是扫描集合，订单状态之后仍可变化。
                    if (jdbc.queryForList(
                                    "SELECT id FROM campaigns WHERE id=? FOR UPDATE",
                                    Long.class,
                                    campaignId)
                            .isEmpty()) throw new BusinessException(ErrorCode.NOT_FOUND);
                    long upper =
                            jdbc.queryForObject(
                                    "SELECT COALESCE(MAX(id),0) FROM fulfillment_orders WHERE campaign_id=?",
                                    Long.class,
                                    campaignId);
                    jdbc.update(
                            "INSERT INTO reconciliation_runs(campaign_id,cutoff_at,upper_order_id) VALUES (?,UTC_TIMESTAMP(3),?)",
                            campaignId,
                            upper);
                    return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
                });
    }

    public RunDetail run(long id) {
        return jdbc
                .query(
                        "SELECT * FROM reconciliation_runs WHERE id=?",
                        (r, n) ->
                                new RunDetail(
                                        r.getString("id"),
                                        r.getString("campaign_id"),
                                        r.getString("state"),
                                        r.getString("last_seen_id"),
                                        r.getString("upper_order_id"),
                                        r.getLong("scanned_count"),
                                        instant(r, "cutoff_at"),
                                        instant(r, "started_at"),
                                        instant(r, "finished_at")),
                        id)
                .stream()
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    /** 领取新批次或接管过期任务，保留上次提交的扫描游标和账目检查结果。 */
    public Optional<Lease> claim() {
        return tx.execute(
                s -> {
                    var rows =
                            jdbc.query(
                                    """
                SELECT * FROM reconciliation_runs WHERE state='READY'
                OR (state='RUNNING' AND lease_until<=UTC_TIMESTAMP(3)) ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED
                """,
                                    (r, n) ->
                                            new Lease(
                                                    r.getLong("id"),
                                                    r.getLong("campaign_id"),
                                                    r.getLong("last_seen_id"),
                                                    r.getLong("upper_order_id"),
                                                    UUID.randomUUID().toString(),
                                                    accounting(r)));
                    if (rows.isEmpty()) return Optional.empty();
                    var lease = rows.getFirst();
                    jdbc.update(
                            """
                UPDATE reconciliation_runs SET state='RUNNING',lease_token=?,
                lease_until=TIMESTAMPADD(SECOND,120,UTC_TIMESTAMP(3)) WHERE id=?
                """,
                            lease.token(),
                            lease.id());
                    return Optional.of(lease);
                });
    }

    /** 以订单编号作游标，在创建任务时冻结的上界内顺序扫描。 */
    public List<Observation> page(Lease lease) {
        // 扫描区间为 (cursor, upperOrderId]；cutoff_at 记录任务时间，实际分页边界以编号为准。
        return jdbc.query(
                """
            SELECT * FROM fulfillment_orders WHERE campaign_id=? AND id>? AND id<=?
            ORDER BY id LIMIT ?
            """,
                this::observation,
                lease.campaignId(),
                lease.cursor(),
                lease.upperOrderId(),
                properties.getBatchSize());
    }

    public boolean renew(Lease lease) {
        // 令牌识别执行者，原游标识别这批工作；已过期租约不能通过续租重新取得提交权。
        return jdbc.update(
                        """
            UPDATE reconciliation_runs SET lease_until=TIMESTAMPADD(SECOND,120,UTC_TIMESTAMP(3))
            WHERE id=? AND state='RUNNING' AND lease_token=? AND last_seen_id=?
                AND lease_until>UTC_TIMESTAMP(3)
            """,
                        lease.id(),
                        lease.token(),
                        lease.cursor())
                == 1;
    }

    public boolean saveAccounting(Lease lease, Accounting accounting) {
        return jdbc.update(
                        """
            UPDATE reconciliation_runs SET accounting_valid=?,accounting_observed_at=?
            WHERE id=? AND state='RUNNING' AND lease_token=? AND last_seen_id=?
                AND lease_until>UTC_TIMESTAMP(3)
            """,
                        accounting.valid(),
                        LocalDateTime.ofInstant(accounting.observedAt(), ZoneOffset.UTC),
                        lease.id(),
                        lease.token(),
                        lease.cursor())
                == 1;
    }

    private Accounting accounting(ResultSet r) throws SQLException {
        var valid = r.getObject("accounting_valid", Boolean.class);
        return valid == null ? null : new Accounting(valid, instant(r, "accounting_observed_at"));
    }

    public Observation order(long id) {
        return readOrder(id, false);
    }

    public Observation lockOrder(long id) {
        return readOrder(id, true);
    }

    private Observation readOrder(long id, boolean locked) {
        return jdbc
                .query(
                        "SELECT * FROM fulfillment_orders WHERE id=?"
                                + (locked ? " FOR UPDATE" : ""),
                        this::observation,
                        id)
                .stream()
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    private Observation observation(ResultSet r, int n) throws SQLException {
        return new Observation(
                r.getLong("id"),
                r.getLong("campaign_id"),
                r.getLong("revision"),
                r.getString("status"),
                r.getString("fulfillment_mode"),
                r.getString("job_state"),
                r.getInt("manual_query_granted_total"),
                instant(r, "expires_at"),
                instant(r, "completed_at"));
    }

    public void lockCampaign(long id) {
        jdbc.queryForObject("SELECT id FROM campaigns WHERE id=? FOR UPDATE", Long.class, id);
    }

    /** 提交批次前锁定并核实租约及原游标，阻止过期执行者提交扫描结果。 */
    public boolean owns(Lease lease) {
        return !jdbc.queryForList(
                        """
            SELECT id FROM reconciliation_runs WHERE id=? AND state='RUNNING' AND lease_token=?
            AND lease_until>UTC_TIMESTAMP(3) AND last_seen_id=? FOR UPDATE
            """,
                        lease.id(),
                        lease.token(),
                        lease.cursor())
                .isEmpty();
    }

    /** 与本批差异一同提交游标；还有订单时释放租约，交由下一轮继续扫描。 */
    public void advance(Lease lease, long cursor, int count, boolean completed) {
        // 即使 owns 已通过，写差异期间仍可能到期；更新失败抛异常，让同一事务中的差异一并回滚。
        int changed =
                jdbc.update(
                        """
            UPDATE reconciliation_runs SET last_seen_id=?,scanned_count=scanned_count+?,state=?,
            lease_token=NULL,lease_until=NULL,finished_at=IF(?,UTC_TIMESTAMP(3),NULL)
            WHERE id=? AND state='RUNNING' AND lease_token=? AND last_seen_id=? AND lease_until>UTC_TIMESTAMP(3)
            """,
                        cursor,
                        count,
                        completed ? "COMPLETED" : "READY",
                        completed,
                        lease.id(),
                        lease.token(),
                        lease.cursor());
        if (changed != 1)
            throw new IllegalStateException("Reconciliation lease expired before batch commit");
    }

    public void insertDiff(
            Lease lease,
            Observation observation,
            DifferenceClassifier.Classification result,
            long version,
            String eventId,
            String evidence) {
        // 唯一键包含任务、订单、差异类型、双方版本和目标事件；同一次观察重放不覆盖原证据。
        jdbc.update(
                """
            INSERT INTO reconciliation_diffs(run_id,order_id,diff_type,observed_revision,supplier_version,target_event_id,evidence,state)
            VALUES (?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE id=id
            """,
                lease.id(),
                observation.id(),
                result.type(),
                observation.revision(),
                version,
                eventId,
                evidence,
                result.state());
    }

    public DiffDetail diff(long id, boolean lock) {
        return jdbc
                .query(
                        "SELECT * FROM reconciliation_diffs WHERE id=?"
                                + (lock ? " FOR UPDATE" : ""),
                        this::diffRow,
                        id)
                .stream()
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    public List<DiffDetail> diffs(long runId, long after, int size) {
        return jdbc.query(
                "SELECT * FROM reconciliation_diffs WHERE run_id=? AND id>? ORDER BY id LIMIT ?",
                this::diffRow,
                runId,
                after,
                size);
    }

    private DiffDetail diffRow(ResultSet r, int n) throws SQLException {
        return new DiffDetail(
                r.getString("id"),
                r.getString("run_id"),
                r.getString("order_id"),
                r.getString("diff_type"),
                r.getLong("observed_revision"),
                r.getLong("supplier_version"),
                r.getString("target_event_id"),
                r.getString("state"),
                r.getString("evidence"),
                instant(r, "created_at"));
    }

    public Optional<Command> command(String id) {
        return jdbc
                .query(
                        "SELECT command_fingerprint,result FROM repair_commands WHERE command_id=?",
                        (r, n) ->
                                new Command(
                                        r.getString(1),
                                        json.readValue(r.getString(2), RepairReceipt.class)),
                        id)
                .stream()
                .findFirst();
    }

    public void insertCommand(
            long diffId, long orderId, long operator, String fingerprint, RepairRequest request) {
        jdbc.update(
                """
            INSERT INTO repair_commands(command_id,diff_id,order_id,expected_revision,command_fingerprint,operation,operator_id,reason)
            VALUES (?,?,?,?,?,?,?,?)
            """,
                request.commandId().toString(),
                diffId,
                orderId,
                request.expectedRevision(),
                fingerprint,
                request.operation().name(),
                operator,
                request.reason());
    }

    /** 保存可供重复请求返回的修复回执，并同步差异处理状态。 */
    public void completeCommand(RepairReceipt receipt) {
        jdbc.update(
                "UPDATE repair_commands SET result=? WHERE command_id=?",
                json.writeValueAsString(receipt),
                receipt.commandId());
        jdbc.update(
                "UPDATE reconciliation_diffs SET state=?,resolved_at=IF(?='RESOLVED',UTC_TIMESTAMP(3),NULL) WHERE id=?",
                receipt.diffState(),
                receipt.diffState(),
                Long.parseLong(receipt.diffId()));
    }

    private static Instant instant(ResultSet r, String column) throws SQLException {
        var value = r.getObject(column, LocalDateTime.class);
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }
}
