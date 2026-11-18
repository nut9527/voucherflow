package com.example.voucherflow.fulfillment.supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.voucherflow.fulfillment.FulfillmentProperties;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/** 验证请求取消、容器停机和事务边界对供应商 HTTP 资源的管理。 */
class HttpSupplierClientLifecycleTest {
    private final HttpClient transport = mock(HttpClient.class);
    private final HttpSupplierClient client =
            new HttpSupplierClient(
                    new FulfillmentProperties(), JsonMapper.builder().build(), transport);
    private final SupplierProtocol.IssueRequest request =
            new SupplierProtocol.IssueRequest("request-1", "course", "subject");

    @Test
    void interruptionCancelsPendingRequestAndPreservesInterruptFlag() {
        var pending = new CompletableFuture<HttpResponse<byte[]>>();
        when(transport.<byte[]>sendAsync(any(), any())).thenReturn(pending);
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> client.call(request, "QUERY"))
                    .isInstanceOf(HttpSupplierClient.Unavailable.class)
                    .hasCauseInstanceOf(InterruptedException.class);
            assertThat(pending).isCancelled();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            // 清除本测试设置的中断标志，避免污染后续测试线程。
            Thread.interrupted();
            client.close();
        }
    }

    @Test
    void closingApplicationContextShutsDownTransport() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(HttpSupplierClient.class, () -> client);
            context.refresh();
        }
        verify(transport).shutdownNow();
    }

    @Test
    void activeTransactionRejectsRemoteCallBeforeSendingRequest() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(() -> client.call(request, "ISSUE"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Supplier HTTP inside transaction");
            verifyNoInteractions(transport);
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
            client.close();
        }
    }
}
