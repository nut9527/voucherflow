package com.example.voucherflow.outbox;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** 验证发送容量的申请与归还，以及提交失败后保留可恢复的消息租约。 */
class OutboxDispatcherTest {
    @Test
    void fullPublisherDoesNotClaimMoreAndStopDrainsOnlyExistingSends() throws Exception {
        var leases = mock(OutboxLeaseService.class);
        var sender = mock(RocketMqEventSender.class);
        var entered = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        var active = new AtomicInteger();
        var maximum = new AtomicInteger();
        when(leases.claim(2)).thenReturn(List.of(event("a"), event("b")));
        when(leases.complete(any(), eq(true), isNull())).thenReturn(true);
        doAnswer(
                        call -> {
                            maximum.accumulateAndGet(active.incrementAndGet(), Math::max);
                            entered.countDown();
                            try {
                                assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
                            } finally {
                                active.decrementAndGet();
                            }
                            return null;
                        })
                .when(sender)
                .send(any());
        var pool = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS, new SynchronousQueue<>());
        try {
            var dispatcher = new OutboxDispatcher(leases, sender, pool, 2);
            dispatcher.tick();
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            for (int i = 0; i < 20; i++) dispatcher.tick();
            verify(leases, times(1)).claim(2);
            assertThat(pool.getQueue()).isEmpty();
            assertThat(maximum.get()).isEqualTo(2);
            dispatcher.stop();
            release.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
            dispatcher.tick();
            verify(leases, times(1)).claim(anyInt());
            verify(leases, times(2)).complete(any(), eq(true), isNull());
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void rejectedSubmissionAndFailedClaimReturnCapacityWithoutInventingCompletion()
            throws Exception {
        var leases = mock(OutboxLeaseService.class);
        var sender = mock(RocketMqEventSender.class);
        when(leases.claim(1))
                .thenThrow(new IllegalStateException("claim unavailable"))
                .thenReturn(List.of(event("reserved")))
                .thenReturn(List.of());
        var rejected = Executors.newSingleThreadExecutor();
        rejected.shutdown();
        var dispatcher = new OutboxDispatcher(leases, sender, rejected, 1);
        dispatcher.tick();
        dispatcher.tick();
        dispatcher.tick();
        verify(leases, times(3)).claim(1);
        verifyNoInteractions(sender);
        verify(leases, never()).complete(any(), anyBoolean(), any());
        // 未提交的租约仍保存在数据库中，由独立的租约扫描任务负责恢复。
        when(leases.claim(1)).thenReturn(List.of(event("recovered")));
        when(leases.complete(any(), eq(true), isNull())).thenReturn(true);
        try (var successorPool = Executors.newSingleThreadExecutor()) {
            var successor = new OutboxDispatcher(leases, sender, successorPool, 1);
            successor.tick();
            successorPool.shutdown();
            assertThat(successorPool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
            verify(sender).send(argThat(event -> event.eventId().equals("recovered")));
        }
    }

    private LeasedEvent event(String id) {
        return new LeasedEvent(id, "test", "{}", id, 1);
    }
}
