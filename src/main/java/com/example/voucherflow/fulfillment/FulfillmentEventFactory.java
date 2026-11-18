package com.example.voucherflow.fulfillment;

import java.util.Map;
import java.util.UUID;

import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import com.example.voucherflow.issuance.model.OrderRow;
import com.example.voucherflow.outbox.DomainEvent;
import com.example.voucherflow.redemption.model.VoucherRow;

/** 根据订单与券的业务事实构造履约事件，并携带当前请求的追踪标识。 */
@Component
public class FulfillmentEventFactory {
        public DomainEvent fulfillmentRequested(OrderRow order, java.time.Instant now) {
                return new DomainEvent(
                                UUID.randomUUID().toString(),
                                "FulfillmentRequested.v1",
                                1,
                                "FULFILLMENT_ORDER",
                                order.getId().toString(),
                                now,
                                MDC.get("traceId"),
                                Map.of(
                                                "orderId",
                                                order.getId().toString(),
                                                "userId",
                                                Long.toString(order.getUserId()),
                                                "campaignId",
                                                Long.toString(order.getCampaignId())));
        }

        public DomainEvent fulfillmentFailed(OrderRow order, java.time.Instant now) {
                return new DomainEvent(
                                UUID.randomUUID().toString(),
                                "FulfillmentFailed.v1",
                                1,
                                "FULFILLMENT_ORDER",
                                order.getId().toString(),
                                now,
                                MDC.get("traceId"),
                                Map.of(
                                                "orderId",
                                                order.getId().toString(),
                                                "userId",
                                                Long.toString(order.getUserId())));
        }

        public DomainEvent voucherIssued(OrderRow order, VoucherRow voucher) {
                return new DomainEvent(
                                UUID.randomUUID().toString(),
                                "VoucherIssued.v1",
                                1,
                                "USER_VOUCHER",
                                voucher.getId().toString(),
                                voucher.getIssuedAt(),
                                MDC.get("traceId"),
                                Map.of(
                                                "userId",
                                                Long.toString(order.getUserId()),
                                                "orderId",
                                                order.getId().toString(),
                                                "voucherId",
                                                voucher.getId().toString()));
        }
}
