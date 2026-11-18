package com.example.voucherflow.protection;

import com.example.voucherflow.common.BusinessException;
import com.example.voucherflow.common.ErrorCode;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/** 合并相同键的并发回源请求，同时限制在途键数量与数据库查询并发数。 */
public final class BoundedSingleFlight<K, V> {
    private final ConcurrentHashMap<K, CompletableFuture<V>> flights = new ConcurrentHashMap<>();
    private final Semaphore keys;
    private final Semaphore database;
    private final Duration timeout;
    private final boolean coalescing;

    public BoundedSingleFlight(
            int maxKeys, int dbConcurrency, Duration timeout, boolean coalescing) {
        this.keys = new Semaphore(maxKeys);
        this.database = new Semaphore(dbConcurrency);
        this.timeout = timeout;
        this.coalescing = coalescing;
    }

    /** 开启合并时，首个请求执行回源，同键后续请求等待并共享结果或异常。 */
    public V load(K key, Supplier<V> loader) {
        if (!coalescing) {
            return query(loader);
        }
        var existing = flights.get(key);
        if (existing != null) {
            return await(existing);
        }
        if (!keys.tryAcquire()) {
            throw busy();
        }
        var flight = new CompletableFuture<V>();
        // 原子登记决定谁负责回源；竞争失败者归还键额度，转为等待已有任务。
        existing = flights.putIfAbsent(key, flight);
        if (existing != null) {
            keys.release();
            return await(existing);
        }
        try {
            V result = query(loader);
            flight.complete(result);
            return result;
        } catch (RuntimeException | Error ex) {
            flight.completeExceptionally(ex);
            throw ex;
        } finally {
            flights.remove(key, flight);
            keys.release();
        }
    }

    // 即使关闭同键请求合并，所有回源操作仍受数据库并发额度约束。
    private V query(Supplier<V> loader) {
        if (!database.tryAcquire()) {
            throw busy();
        }
        try {
            return loader.get();
        } finally {
            database.release();
        }
    }

    // 等待者超时只结束本次等待，不取消负责回源的任务或提前释放其额度。
    private V await(CompletableFuture<V> flight) {
        try {
            return flight.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw busy();
        } catch (TimeoutException ex) {
            throw busy();
        } catch (ExecutionException ex) {
            if (ex.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (ex.getCause() instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException(ex.getCause());
        }
    }

    private BusinessException busy() {
        return new BusinessException(ErrorCode.SYSTEM_BUSY);
    }
}
