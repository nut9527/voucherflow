package com.example.voucherflow;

import static org.assertj.core.api.Assertions.*;

import com.example.voucherflow.fulfillment.FulfillmentProperties;
import com.example.voucherflow.fulfillment.supplier.HttpSupplierClient;
import com.example.voucherflow.fulfillment.supplier.SupplierProtocol;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** 验证供应商响应体读取的整体超时及反序列化前的大小限制。 */
class SupplierHttpClientTest {
    @Test
    void entireBodyReadHasDeadlineAfterHeadersArrive() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var executor = Executors.newSingleThreadExecutor();
        server.setExecutor(executor);
        server.createContext(
                "/supplier/fulfillments/",
                exchange -> {
                    exchange.sendResponseHeaders(200, 100);
                    exchange.getResponseBody().write('{');
                    exchange.getResponseBody().flush();
                    try {
                        new java.util.concurrent.CountDownLatch(1).await(8, TimeUnit.SECONDS);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    } finally {
                        exchange.close();
                    }
                });
        server.start();
        try (var client = client(server)) {
            long start = System.nanoTime();
            assertThatThrownBy(() -> client.call(request(), "QUERY"))
                    .isInstanceOf(HttpSupplierClient.Unavailable.class);
            assertThat(Duration.ofNanos(System.nanoTime() - start))
                    .isLessThan(Duration.ofSeconds(7));
        } finally {
            server.stop(0);
            executor.shutdownNow();
        }
    }

    @Test
    void oversizedBodyIsRejectedBeforeDeserialization() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/supplier/fulfillments/",
                exchange -> {
                    byte[] bytes = new byte[20000];
                    exchange.sendResponseHeaders(200, bytes.length);
                    try {
                        exchange.getResponseBody().write(bytes);
                    } finally {
                        exchange.close();
                    }
                });
        server.start();
        try (var client = client(server)) {
            assertThatThrownBy(() -> client.call(request(), "QUERY"))
                    .isInstanceOf(HttpSupplierClient.Unavailable.class);
        } finally {
            server.stop(0);
        }
    }

    private HttpSupplierClient client(HttpServer server) {
        var settings = new FulfillmentProperties();
        settings.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        return new HttpSupplierClient(settings, JsonMapper.builder().build());
    }

    private SupplierProtocol.IssueRequest request() {
        return new SupplierProtocol.IssueRequest(UUID.randomUUID().toString(), "course", "subject");
    }
}
