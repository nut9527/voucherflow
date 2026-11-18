package com.example.vfsupplier;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** 通过数据库租约发送持久化回调，失败后按退避计划重试。 */
@Component
public class SupplierCallbackWorker {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final SupplierProperties properties;
    private final DemoFaults faults;
    private final HttpClient client =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    private record Job(String id, String payload, String token) {}

    /** 停机时取消未完成回调；未确认的任务由持久化租约恢复。 */
    @jakarta.annotation.PreDestroy
    void close() {
        client.shutdownNow();
    }

    public SupplierCallbackWorker(
            JdbcTemplate jdbc,
            TransactionTemplate tx,
            SupplierProperties properties,
            DemoFaults faults) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.properties = properties;
        this.faults = faults;
    }

    /** 先在短事务中领取一条回调，再在事务外执行网络请求。 */
    @Scheduled(fixedDelay = 500)
    public void tick() {
        if (faults.callbacksPaused()) return;
        Job job =
                tx.execute(
                        status -> {
                            // 回收过期租约；达到尝试上限的任务进入失败态，其余重新等待发送。
                            List<String> expired =
                                    jdbc.queryForList(
                                            "SELECT event_id FROM callback_jobs WHERE state='SENDING' AND lease_until<=UTC_TIMESTAMP(3) ORDER BY lease_until,event_id LIMIT 16 FOR UPDATE SKIP LOCKED",
                                            String.class);
                            for (String id : expired)
                                jdbc.update(
                                        "UPDATE callback_jobs SET state=CASE WHEN attempt_count>=20 THEN 'FAILED' ELSE 'PENDING' END,lease_token=NULL,lease_until=NULL WHERE event_id=?",
                                        id);
                            // 跳过其他执行器已锁定的行，以独立租约令牌标识本轮发送。
                            var ready =
                                    jdbc.query(
                                            "SELECT event_id,payload FROM callback_jobs WHERE state='PENDING' AND next_send_at<=UTC_TIMESTAMP(3) ORDER BY next_send_at,event_id LIMIT 1 FOR UPDATE SKIP LOCKED",
                                            (r, n) ->
                                                    new Job(
                                                            r.getString(1),
                                                            r.getString(2),
                                                            UUID.randomUUID().toString()));
                            if (ready.isEmpty()) return null;
                            var selected = ready.getFirst();
                            jdbc.update(
                                    "UPDATE callback_jobs SET state='SENDING',lease_token=?,lease_until=TIMESTAMPADD(SECOND,15,UTC_TIMESTAMP(3)),attempt_count=attempt_count+1 WHERE event_id=?",
                                    selected.token(),
                                    selected.id());
                            return selected;
                        });
        if (job == null) return;
        boolean accepted = false;
        try {
            byte[] body = job.payload().getBytes(StandardCharsets.UTF_8);
            String timestamp = Long.toString(Instant.now().getEpochSecond());
            var request =
                    HttpRequest.newBuilder(URI.create(properties.getCallbackUrl()))
                            .timeout(Duration.ofSeconds(5))
                            .header("Content-Type", "application/json")
                            .header("X-Supplier-Timestamp", timestamp)
                            .header(
                                    "X-Supplier-Signature",
                                    Protocol.sign(properties.getCallbackSecret(), timestamp, body))
                            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                            .build();
            int code = client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            accepted = code >= 200 && code < 300;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (java.io.IOException ex) {
            // 发送失败交由持久化任务安排重试。
        }
        // 仅当前且未过期的租约能提交发送结果，避免旧执行器覆盖新的任务状态。
        if (accepted)
            jdbc.update(
                    "UPDATE callback_jobs SET state='DELIVERED',lease_token=NULL,lease_until=NULL WHERE event_id=? AND state='SENDING' AND lease_token=? AND lease_until>UTC_TIMESTAMP(3)",
                    job.id(),
                    job.token());
        else
            jdbc.update(
                    "UPDATE callback_jobs SET state=CASE WHEN attempt_count>=20 THEN 'FAILED' ELSE 'PENDING' END,next_send_at=TIMESTAMPADD(SECOND,LEAST(60,POW(2,attempt_count)),UTC_TIMESTAMP(3)),lease_token=NULL,lease_until=NULL WHERE event_id=? AND state='SENDING' AND lease_token=? AND lease_until>UTC_TIMESTAMP(3)",
                    job.id(),
                    job.token());
    }
}
