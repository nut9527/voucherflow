package com.example.voucherflow.fulfillment.supplier;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.example.voucherflow.fulfillment.FulfillmentProperties;

import tools.jackson.databind.ObjectMapper;

/** 封装供应商发放与查单 HTTP 协议，将通信失败归类为结果不可用。 */
@Component
public class HttpSupplierClient implements AutoCloseable {
    public static class Unavailable extends RuntimeException {
        public Unavailable(Throwable cause) {
            super("Supplier result unavailable", cause);
        }
    }

    private final FulfillmentProperties properties;
    private final ObjectMapper json;
    private final HttpClient client;

    @org.springframework.beans.factory.annotation.Autowired
    public HttpSupplierClient(FulfillmentProperties properties, ObjectMapper json) {
        this(
                properties,
                json,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
    }

    HttpSupplierClient(FulfillmentProperties properties, ObjectMapper json, HttpClient client) {
        this.properties = properties;
        this.json = json;
        this.client = client;
    }

    /** 在数据库事务之外发起远程请求，避免网络等待占用业务行锁。 */
    public SupplierProtocol.Result call(SupplierProtocol.IssueRequest request, String action) {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Supplier HTTP inside transaction");
        try {
            var builder = HttpRequest.newBuilder()
                    .timeout(Duration.ofSeconds(5))
                    .header("Authorization", "Bearer " + properties.getApiToken());
            if ("ISSUE".equals(action)) {
                builder.uri(URI.create(properties.getBaseUrl() + "/supplier/fulfillments"))
                        .header("Content-Type", "application/json")
                        .POST(
                                HttpRequest.BodyPublishers.ofString(
                                        json.writeValueAsString(request)));
            } else {
                builder.uri(
                        URI.create(
                                properties.getBaseUrl()
                                        + "/supplier/fulfillments/"
                                        + request.requestNo()))
                        .GET();
            }
            var pending = client.sendAsync(builder.build(), ignored -> new BoundedBody());
            HttpResponse<byte[]> response;
            try {
                response = pending.get(5, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                // 调用线程取消时同步取消网络请求，避免旧请求继续占用连接。
                pending.cancel(true);
                throw ex;
            } catch (java.util.concurrent.ExecutionException
                    | java.util.concurrent.TimeoutException ex) {
                pending.cancel(true);
                throw new Unavailable(ex);
            }
            byte[] bytes = response.body();
            if (response.statusCode() != 200)
                throw new IllegalStateException("Supplier HTTP result unavailable");
            var result = json.readValue(bytes, SupplierProtocol.Result.class);
            result.validate();
            // 未找到记录时供应商没有业务字段，使用本次请求补齐证据的订单关联信息。
            if ("NOT_FOUND".equals(result.status())) {
                return new SupplierProtocol.Result(
                        request.requestNo(),
                        request.productCode(),
                        request.subjectRef(),
                        "NOT_FOUND",
                        null,
                        null);
            }
            return result;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new Unavailable(ex);
        } catch (RuntimeException ex) {
            throw new Unavailable(ex);
        }
    }

    /** 停机时取消未完成请求，释放客户端拥有的连接与执行资源。 */
    @jakarta.annotation.PreDestroy
    @Override
    public void close() {
        client.shutdownNow();
    }

    /** 流式累计响应体大小，在读取过程中限制报文占用。 */
    private static class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
        private java.util.concurrent.Flow.Subscription subscription;
        private int size;

        public java.util.concurrent.CompletionStage<byte[]> getBody() {
            return delegate.getBody();
        }

        public void onSubscribe(java.util.concurrent.Flow.Subscription value) {
            subscription = value;
            delegate.onSubscribe(value);
        }

        public void onNext(java.util.List<java.nio.ByteBuffer> buffers) {
            for (var buffer : buffers)
                size += buffer.remaining();
            if (size > 16384) {
                subscription.cancel();
                delegate.onError(new java.io.IOException("Supplier response too large"));
            } else
                delegate.onNext(buffers);
        }

        public void onError(Throwable error) {
            delegate.onError(error);
        }

        public void onComplete() {
            delegate.onComplete();
        }
    }
}
