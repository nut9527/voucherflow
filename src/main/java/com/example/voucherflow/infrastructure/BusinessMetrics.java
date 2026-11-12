package com.example.voucherflow.infrastructure;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 定时汇总消息积压、履约停滞与对账差异，供监控系统读取业务指标。 */
@Component
public class BusinessMetrics {
    private static final Logger log = LoggerFactory.getLogger(BusinessMetrics.class);
    private final JdbcTemplate jdbc;
    private final AtomicLong pending = new AtomicLong();
    private final AtomicLong failedEvents = new AtomicLong();
    private final AtomicLong paused = new AtomicLong();
    private final AtomicLong oldestReservation = new AtomicLong();
    private final AtomicLong oldestPendingEvent = new AtomicLong();
    private final AtomicLong openDifferences = new AtomicLong();
    private final AtomicLong lastRefresh = new AtomicLong();

    public BusinessMetrics(JdbcTemplate jdbc, MeterRegistry registry) {
        this.jdbc = jdbc;
        registry.gauge("vf.outbox.pending", pending);
        registry.gauge("vf.outbox.failed", failedEvents);
        registry.gauge("vf.outbox.oldest.pending.seconds", oldestPendingEvent);
        registry.gauge("vf.fulfillment.paused", paused);
        registry.gauge("vf.fulfillment.oldest.reservation.seconds", oldestReservation);
        registry.gauge("vf.reconciliation.open", openDifferences);
        registry.gauge("vf.metrics.last.refresh.epoch.seconds", lastRefresh);
    }

    @Scheduled(fixedDelayString = "${voucherflow.metrics.interval-ms:15000}")
    public void refresh() {
        try {
            pending.set(count("SELECT COUNT(*) FROM outbox_events WHERE state IN ('PENDING','SENDING')"));
            failedEvents.set(count("SELECT COUNT(*) FROM outbox_events WHERE state='FAILED'"));
            oldestPendingEvent.set(count("""
                    SELECT COALESCE(TIMESTAMPDIFF(SECOND,MIN(created_at),UTC_TIMESTAMP(3)),0)
                    FROM (
                        SELECT MIN(created_at) AS created_at FROM outbox_events WHERE state='PENDING'
                        UNION ALL
                        SELECT MIN(created_at) FROM outbox_events WHERE state='SENDING'
                    ) oldest
                    """));
            paused.set(count("SELECT COUNT(*) FROM fulfillment_orders WHERE job_state='PAUSED'"));
            oldestReservation.set(count("""
                    SELECT COALESCE(TIMESTAMPDIFF(SECOND,MIN(created_at),UTC_TIMESTAMP(3)),0)
                    FROM (
                        SELECT MIN(created_at) AS created_at FROM fulfillment_orders WHERE status='PENDING'
                        UNION ALL
                        SELECT MIN(created_at) FROM fulfillment_orders WHERE status='UNKNOWN'
                    ) oldest
                    """));
            openDifferences.set(count("""
                    SELECT COUNT(*) FROM reconciliation_diffs
                    WHERE state IN ('OPEN','NEEDS_RECHECK','REVIEW_ONLY')
                    """));
            // 全部查询完成后才更新时间，便于监控判断本轮指标是否刷新成功。
            lastRefresh.set(count("SELECT UNIX_TIMESTAMP(UTC_TIMESTAMP())"));
        } catch (DataAccessException ex) {
            log.warn("Business metrics refresh unavailable", ex);
        }
    }

    private long count(String sql) {
        return jdbc.queryForObject(sql, Long.class);
    }
}
