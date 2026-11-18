package com.example.voucherflow.protection;

import static org.assertj.core.api.Assertions.*;

import com.example.voucherflow.common.BusinessException;
import com.example.voucherflow.common.ErrorCode;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

/** 验证同键并发共享结果、等待超时及回源失败时的额度释放。 */
class BoundedSingleFlightTest {
    @Test
    void concurrentSameKeySharesOneResult() throws Exception {
        var flights = new BoundedSingleFlight<String, Integer>(2, 1, Duration.ofSeconds(3), true);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var leader =
                new FutureTask<>(
                        () ->
                                flights.load(
                                        "a",
                                        () -> {
                                            calls.incrementAndGet();
                                            entered.countDown();
                                            await(release);
                                            return 42;
                                        }));
        Thread.ofPlatform().start(leader);
        assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
        var follower = new FutureTask<>(() -> flights.load("a", calls::incrementAndGet));
        Thread thread = Thread.ofPlatform().start(follower);
        try {
            waitUntilWaiting(thread);
            assertThat(calls).hasValue(1);
        } finally {
            release.countDown();
        }
        assertThat(leader.get(2, TimeUnit.SECONDS)).isEqualTo(42);
        assertThat(follower.get(2, TimeUnit.SECONDS)).isEqualTo(42);
        assertThat(calls).hasValue(1);
    }

    @Test
    void waiterTimeoutKeepsLeaderAndDatabaseSlot() throws Exception {
        var flights = new BoundedSingleFlight<String, Integer>(2, 1, Duration.ofMillis(20), true);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var leader =
                new FutureTask<>(
                        () ->
                                flights.load(
                                        "a",
                                        () -> {
                                            entered.countDown();
                                            await(release);
                                            return 1;
                                        }));
        Thread.ofPlatform().start(leader);
        assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
        try {
            assertBusy(() -> flights.load("a", () -> 99));
            assertBusy(() -> flights.load("a", () -> 99));
            assertBusy(() -> flights.load("b", () -> 99));
        } finally {
            release.countDown();
        }
        assertThat(leader.get(2, TimeUnit.SECONDS)).isEqualTo(1);
        assertThat(flights.load("b", () -> 2)).isEqualTo(2);
    }

    @Test
    void loaderFailureReleasesKeyAndDatabasePermits() {
        var flights = new BoundedSingleFlight<String, Integer>(1, 1, Duration.ofMillis(100), true);
        assertThatThrownBy(
                        () ->
                                flights.load(
                                        "a",
                                        () -> {
                                            throw new IllegalStateException("database failed");
                                        }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("database failed");
        assertThat(flights.load("a", () -> 2)).isEqualTo(2);
        assertThat(flights.load("b", () -> 3)).isEqualTo(3);
    }

    @Test
    void disablingCoalescingStillBoundsDatabaseConcurrency() throws Exception {
        var flights = new BoundedSingleFlight<String, Integer>(1, 1, Duration.ofMillis(100), false);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var leader =
                new FutureTask<>(
                        () ->
                                flights.load(
                                        "a",
                                        () -> {
                                            entered.countDown();
                                            await(release);
                                            return 1;
                                        }));
        Thread.ofPlatform().start(leader);
        assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
        try {
            assertBusy(() -> flights.load("a", () -> 2));
        } finally {
            release.countDown();
        }
        assertThat(leader.get(2, TimeUnit.SECONDS)).isEqualTo(1);
        assertThat(flights.load("a", () -> 2)).isEqualTo(2);
    }

    private static void assertBusy(ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        ex -> assertThat(ex.code()).isEqualTo(ErrorCode.SYSTEM_BUSY));
    }

    private static void waitUntilWaiting(Thread thread) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (thread.getState() != Thread.State.TIMED_WAITING && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertThat(thread.getState()).isEqualTo(Thread.State.TIMED_WAITING);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(3, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Test barrier timeout");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }
}
