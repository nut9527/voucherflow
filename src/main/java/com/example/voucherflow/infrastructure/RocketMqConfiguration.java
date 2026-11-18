package com.example.voucherflow.infrastructure;

import com.example.voucherflow.fulfillment.FulfillmentJobService;
import com.example.voucherflow.notification.NotificationService;
import com.example.voucherflow.outbox.ConsumedEventStore;
import com.example.voucherflow.outbox.EventCodec;
import com.example.voucherflow.outbox.EventLogContext;
import com.example.voucherflow.outbox.MessagingProperties;
import com.example.voucherflow.outbox.OutboxLeaseService;
import com.example.voucherflow.outbox.RocketMqEventSender;
import java.time.Duration;
import java.util.Map;
import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.ClientException;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.StaticSessionCredentialsProvider;
import org.apache.rocketmq.client.apis.consumer.FilterExpression;
import org.apache.rocketmq.client.apis.consumer.FilterExpressionType;
import org.apache.rocketmq.client.apis.consumer.SimpleConsumer;
import org.apache.rocketmq.client.apis.producer.Producer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 组装消息客户端，将履约消息路由到持久任务登记，将终态消息路由到通知消费。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MessagingProperties.class)
public class RocketMqConfiguration {
    @Bean(initMethod = "start", destroyMethod = "close")
    @ConditionalOnProperty(name = "voucherflow.messaging.enabled", havingValue = "true")
    RocketMqRuntime rocketMqRuntime(
            MessagingProperties properties,
            EventCodec codec,
            OutboxLeaseService leases,
            ConsumedEventStore receipts,
            FulfillmentJobService jobs,
            NotificationService notifications,
            com.example.voucherflow.common.ExecutionProbe probe) {
        return new RocketMqRuntime(
                properties,
                leases,
                () -> connect(properties, codec, probe),
                message -> {
                    var event = codec.decode(message);
                    try (var context = EventLogContext.open(event)) {
                        // 消费阶段只登记持久任务；实际远程履约由独立执行器推进。
                        receipts.consume(
                                "vf_fulfillment_worker",
                                event,
                                () -> jobs.registerRequested(event));
                    }
                },
                message -> {
                    var event = codec.decode(message);
                    try (var context = EventLogContext.open(event)) {
                        notifications.consume(event);
                    }
                },
                probe);
    }

    private RocketMqRuntime.Clients connect(
            MessagingProperties properties,
            EventCodec codec,
            com.example.voucherflow.common.ExecutionProbe probe)
            throws ClientException {
        var provider = ClientServiceProvider.loadService();
        var builder =
                ClientConfiguration.newBuilder()
                        .setEndpoints(properties.endpoints())
                        .setRequestTimeout(properties.requestTimeout())
                        .enableSsl(properties.tls());
        if (!properties.accessKey().isBlank()) {
            builder.setCredentialProvider(
                    new StaticSessionCredentialsProvider(
                            properties.accessKey(), properties.secretKey()));
        }
        var config = builder.build();
        SimpleConsumer fulfillment = null;
        SimpleConsumer notification = null;
        Producer producer = null;
        try {
            fulfillment =
                    provider.newSimpleConsumerBuilder()
                            .setClientConfiguration(config)
                            .setConsumerGroup("vf_fulfillment_worker")
                            .setAwaitDuration(Duration.ofSeconds(5))
                            .setSubscriptionExpressions(
                                    Map.of(
                                            EventCodec.FULFILLMENT_TOPIC,
                                            new FilterExpression(
                                                    "FulfillmentRequestedV1",
                                                    FilterExpressionType.TAG)))
                            .build();
            notification =
                    provider.newSimpleConsumerBuilder()
                            .setClientConfiguration(config)
                            .setConsumerGroup(NotificationService.CONSUMER)
                            .setAwaitDuration(Duration.ofSeconds(5))
                            .setSubscriptionExpressions(
                                    Map.of(
                                            EventCodec.NOTIFICATION_TOPIC,
                                            new FilterExpression(
                                                    "VoucherIssuedV1 || FulfillmentFailedV1",
                                                    FilterExpressionType.TAG)))
                            .build();
            // 单次发送只尝试一次，后续重试次数与调度交给数据库中的 Outbox 状态管理。
            producer =
                    provider.newProducerBuilder()
                            .setClientConfiguration(config)
                            .setTopics(EventCodec.FULFILLMENT_TOPIC, EventCodec.NOTIFICATION_TOPIC)
                            .setMaxAttempts(1)
                            .build();
            return new RocketMqRuntime.Clients(
                    producer,
                    fulfillment,
                    notification,
                    new RocketMqEventSender(provider, producer, codec, probe));
        } catch (ClientException | RuntimeException ex) {
            closeQuietly(producer);
            closeQuietly(notification);
            closeQuietly(fulfillment);
            throw ex;
        }
    }

    private void closeQuietly(AutoCloseable resource) {
        if (resource != null) {
            try {
                resource.close();
            } catch (Exception ignored) {
            }
        }
    }
}
