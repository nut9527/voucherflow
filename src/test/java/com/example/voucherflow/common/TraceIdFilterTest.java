package com.example.voucherflow.common;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 验证请求正常或异常结束时清理追踪编号，同时保留其他日志上下文。 */
class TraceIdFilterTest {

    private final TraceIdFilter filter = new TraceIdFilter();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void clearsTraceIdAfterTheRequestAndPreservesOtherContext() throws Exception {
        var response = new MockHttpServletResponse();
        MDC.put("otherContext", "preserved");

        filter.doFilter(new MockHttpServletRequest(), response, (request, servletResponse) -> {
            assertThat(MDC.get("traceId")).isNotBlank().isEqualTo(response.getHeader("X-Trace-Id"));
        });

        assertThat(MDC.get("traceId")).isNull();
        assertThat(MDC.get("otherContext")).isEqualTo("preserved");
    }

    @Test
    void clearsTraceIdWhenTheRequestThrows() {
        var response = new MockHttpServletResponse();
        var failure = new ServletException("downstream failure");

        assertThatThrownBy(() -> filter.doFilter(new MockHttpServletRequest(), response, (request, servletResponse) -> {
            assertThat(MDC.get("traceId")).isNotBlank().isEqualTo(response.getHeader("X-Trace-Id"));
            throw failure;
        })).isSameAs(failure);

        assertThat(MDC.get("traceId")).isNull();
    }
}
