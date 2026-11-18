package com.example.voucherflow.infrastructure;

import com.example.voucherflow.common.ExecutionProbe;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** 为常规运行提供空探针，为演练环境提供可由文件控制的暂停节点。 */
@Configuration(proxyBeanMethods = false)
public class ExecutionProbeConfiguration {
    @Bean
    @Profile("!demo")
    ExecutionProbe noExecutionProbe() {
        return (point, identity) -> {};
    }

    @Bean
    @Profile("demo")
    ExecutionProbe fileExecutionProbe(
            @Value("${voucherflow.demo.probe-point:}") String selected,
            @Value("${voucherflow.demo.run-id:}") String runId) {
        if (selected.isEmpty()) {
            return (point, identity) -> {};
        }
        if (!Set.of("AFTER_FULFILLMENT_ACK", "AFTER_SUPPLIER_RESULT", "AFTER_OUTBOX_SEND")
                        .contains(selected)
                || !runId.matches("[a-zA-Z0-9-]{1,64}")) {
            throw new IllegalArgumentException("Invalid demo execution probe");
        }
        Path directory =
                Path.of(System.getProperty("user.dir"), ".local", "faults", runId)
                        .toAbsolutePath()
                        .normalize();
        // 每个进程只命中一次探针，避免同一演练中的后续任务重复暂停。
        var hit = new AtomicBoolean();
        return (point, identity) -> {
            if (!selected.equals(point) || !hit.compareAndSet(false, true)) {
                return;
            }
            try {
                Files.createDirectories(directory);
                // reached 告知脚本已到达故障点，release 文件用于恢复执行。
                Files.writeString(
                        directory.resolve("reached"),
                        point + " " + identity + " " + ProcessHandle.current().pid(),
                        StandardOpenOption.CREATE_NEW);
                long deadline = System.nanoTime() + Duration.ofMinutes(2).toNanos();
                while (!Files.exists(directory.resolve("release"))) {
                    if (System.nanoTime() >= deadline) {
                        throw new IllegalStateException("Demo execution probe expired");
                    }
                    Thread.sleep(50);
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(ex);
            } catch (java.io.IOException ex) {
                throw new IllegalStateException("Cannot persist demo probe", ex);
            }
        };
    }
}
