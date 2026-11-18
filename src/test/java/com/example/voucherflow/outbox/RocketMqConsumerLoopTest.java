package com.example.voucherflow.outbox;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.rocketmq.client.apis.consumer.SimpleConsumer;
import org.apache.rocketmq.client.apis.message.MessageView;
import org.junit.jupiter.api.Test;

/** 验证消费提交先于消息确认，并检查接收容量与客户端关闭边界。 */
class RocketMqConsumerLoopTest {
    @Test
    void committedHandlerRunsBeforeAckAndFailureNeverAcknowledges() throws Exception {
        var consumer = mock(SimpleConsumer.class);
        var message = mock(MessageView.class);
        when(consumer.receive(anyInt(), any())).thenReturn(List.of(message));
        var called = new AtomicInteger();
        doAnswer(
                        invocation -> {
                            assertThat(called.get()).isEqualTo(1);
                            return null;
                        })
                .when(consumer)
                .ack(message);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var loop =
                    new RocketMqConsumerLoop(
                            "test",
                            consumer,
                            m -> called.incrementAndGet(),
                            executor,
                            1,
                            Duration.ofSeconds(30));
            loop.receiveTick();
            executor.shutdown();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
            verify(consumer).ack(message);
        }
        clearInvocations(consumer);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var loop =
                    new RocketMqConsumerLoop(
                            "test",
                            consumer,
                            m -> {
                                throw new IllegalStateException();
                            },
                            executor,
                            1,
                            Duration.ofSeconds(30));
            loop.receiveTick();
            executor.shutdown();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
            verify(consumer, never()).ack(any());
        }
    }

    @Test
    void capacityIsReservedBeforeReceiveAndClosedClientNeverGetsNewAck() throws Exception {
        var consumer = mock(SimpleConsumer.class);
        var message = mock(MessageView.class);
        when(consumer.receive(anyInt(), any())).thenReturn(List.of(message));
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var loop =
                    new RocketMqConsumerLoop(
                            "test",
                            consumer,
                            m -> {
                                entered.countDown();
                                try {
                                    release.await(5, TimeUnit.SECONDS);
                                } catch (InterruptedException ex) {
                                    Thread.currentThread().interrupt();
                                }
                            },
                            executor,
                            1,
                            Duration.ofSeconds(30));
            try {
                loop.receiveTick();
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                loop.receiveTick();
                verify(consumer, times(1)).receive(eq(1), any());
                loop.stop();
                loop.closeClient();
            } finally {
                release.countDown();
            }
            executor.shutdown();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
            verify(consumer, never()).ack(any());
            verify(consumer).close();
        }
    }
}
