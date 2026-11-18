package com.example.voucherflow.fulfillment;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.stereotype.Service;

import com.example.voucherflow.common.BusinessException;
import com.example.voucherflow.common.ErrorCode;
import com.example.voucherflow.fulfillment.supplier.SupplierProtocol;

import tools.jackson.databind.ObjectMapper;

/** 验证回调时效、签名和订单关联，将供应商结果持久化为待处理证据。 */
@Service
public class SupplierCallbackService {
    private final FulfillmentProperties properties;
    private final SupplierStore store;
    private final SupplierEvidenceService evidence;
    private final ObjectMapper json;
    private final Clock clock;

    public SupplierCallbackService(
            FulfillmentProperties properties,
            SupplierStore store,
            SupplierEvidenceService evidence,
            ObjectMapper json,
            Clock clock) {
        this.properties = properties;
        this.store = store;
        this.evidence = evidence;
        this.json = json;
        this.clock = clock;
    }

    /** 验证原始报文签名后存证，业务终态由后台回执处理流程推进。 */
    public void accept(String timestamp, String signature, byte[] body) {
        if (!properties.isEnabled() || body.length > 16384) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        long seconds;
        try {
            seconds = Long.parseLong(timestamp);
        } catch (NumberFormatException ex) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        // 签名覆盖时间戳和原始字节，不能先反序列化再重新生成待验签内容。
        long now = clock.instant().getEpochSecond();
        // 时间窗口约束报文时效，窗口内的重复投递仍由回调事件编号去重，两者承担不同职责。
        if (seconds < now - 300
                || seconds > now + 300
                || !MessageDigest.isEqual(
                        SupplierProtocol.sign(properties.getCallbackSecret(), timestamp, body)
                                .getBytes(StandardCharsets.US_ASCII),
                        signature.getBytes(StandardCharsets.US_ASCII))) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        SupplierProtocol.Callback callback;
        try {
            callback = json.readValue(body, SupplierProtocol.Callback.class);
            if (callback.eventId() == null
                    || callback.eventId().isBlank()
                    || callback.eventId().length() > 64) {
                throw new IllegalArgumentException();
            }
            callback.result().validate();
        } catch (RuntimeException ex) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
        SupplierStore.Order order;
        try {
            order = store.byRequest(callback.requestNo());
        } catch (EmptyResultDataAccessException ex) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        try {
            // 同一事件重投复用已存回执；返回成功仅确认存证完成，即使随后进程退出仍能由后台继续应用。
            evidence.record(
                    order.id(),
                    "callback:" + callback.eventId(),
                    "CALLBACK",
                    null,
                    callback.result());
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.SUPPLIER_EVIDENCE_CONFLICT);
        }
    }
}
