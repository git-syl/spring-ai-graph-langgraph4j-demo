package com.example.refunddemo;

import com.example.refunddemo.tool.OrderRefundTools;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.support.ToolCallbacks;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderRefundToolsTest {
    private final OrderRefundTools tools = new OrderRefundTools(Clock.systemUTC());
    private final ToolContext authorized = new ToolContext(Map.of("refundAuthorized", true));

    @Test
    void authorizationIsServerSideAndAbsentFromModelSchema() {
        assertThatThrownBy(() -> tools.createRefund("O-1001", new BigDecimal("99"), "原因", new ToolContext(Map.of())))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> tools.createRefund("O-1001", BigDecimal.ONE, "原因", authorized))
                .isInstanceOf(IllegalArgumentException.class);
        var callback = java.util.Arrays.stream(ToolCallbacks.from(tools))
                .filter(tool -> tool.getToolDefinition().name().equals("create_refund")).findFirst().orElseThrow();
        assertThat(callback.getToolDefinition().inputSchema()).doesNotContain("refundAuthorized", "threadId", "context");
    }

    @Test
    void concurrentCallsUseOneRefundReceiptPerOrder() throws Exception {
        Callable<String> refund = () -> tools.createRefund("O-1001", new BigDecimal("99"), "原因", authorized).refundId();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var results = executor.invokeAll(List.of(refund, refund));
            assertThat(results.get(0).get()).isEqualTo(results.get(1).get());
        }
    }
}
