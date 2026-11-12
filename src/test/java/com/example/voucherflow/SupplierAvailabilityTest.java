package com.example.voucherflow;

import com.example.voucherflow.common.BusinessException;
import com.example.voucherflow.common.ErrorCode;
import com.example.voucherflow.fulfillment.*;
import com.example.voucherflow.fulfillment.supplier.HttpSupplierClient;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 验证供应商能力关闭或配置不匹配时的查询结果，并区分数据库故障。 */
class SupplierAvailabilityTest {
    private final SupplierStore store=mock(SupplierStore.class);
    private final HttpSupplierClient client=mock(HttpSupplierClient.class);
    private final FulfillmentProperties properties=new FulfillmentProperties();
    private final SupplierEvidenceService service=new SupplierEvidenceService(store,client,properties,mock(TransactionTemplate.class));
    private final SupplierStore.Order order=mock(SupplierStore.Order.class);
    @Test void disabledCapabilityReturnsUnavailableWithoutHttpAndRejectsRepair() {
        when(store.find(1)).thenReturn(order);
        assertUnavailable();verifyNoInteractions(client);
    }
    @Test void unmatchedFrozenSupplierOrContractReturnsUnavailable() {
        properties.setEnabled(true);when(store.find(1)).thenReturn(order);
        when(order.supplierId()).thenReturn("another-supplier");when(order.contractVersion()).thenReturn("v1");
        assertUnavailable();
        when(order.supplierId()).thenReturn("simulator");when(order.contractVersion()).thenReturn("another-contract");
        assertUnavailable();verifyNoInteractions(client);
    }
    @Test void databaseFailureIsNotClassifiedAsSupplierUnavailability() {
        var failure=new DataAccessResourceFailureException("database unavailable");when(store.find(1)).thenThrow(failure);
        assertThatThrownBy(()->service.observe(1)).isSameAs(failure);
        assertThatThrownBy(()->service.queryForRepair(1)).isSameAs(failure);
        verifyNoInteractions(client);
    }
    private void assertUnavailable() {
        assertThat(service.observe(1)).isEqualTo(new ReceiptEvidence(0,"UNAVAILABLE",null,null));
        assertThatThrownBy(()->service.queryForRepair(1)).isInstanceOfSatisfying(BusinessException.class,error->assertThat(error.code()).isEqualTo(ErrorCode.SYSTEM_BUSY));
    }
}
