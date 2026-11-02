package com.example.refunddemo;

import com.example.refunddemo.refund.graph.RefundStateSerializer;
import com.example.refunddemo.refund.schema.*;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RefundSerializationTest {
    @Test
    void businessRecordsAndMessagesSurviveNormalStateClone() throws Exception {
        var call = new AssistantMessage.ToolCall("call-1", "function", "get_order", "{\"orderId\":\"O-1001\"}");
        var user = new UserMessage("O-1001 请退款");
        var assistant = AssistantMessage.builder().content("").toolCalls(List.of(call)).build();
        var response = ToolResponseMessage.builder().responses(List.of(
                new ToolResponseMessage.ToolResponse("call-1", "get_order", "{\"status\":\"PAID\"}"))).build();
        var state = new RefundState(Map.of(
                RefundState.ORDER, new Order("O-1001", "PAID", new BigDecimal("9000000000.123456789"), Instant.parse("2026-11-01T00:00:00.123456789Z"), false),
                RefundState.COMMAND, new RefundCommand("O-1001", "质量问题"),
                RefundState.POLICY, new PolicyDecision(true, false, "允许", new BigDecimal("99.01")),
                RefundState.REFUND, new RefundExecution("SUCCESS", "RF-1001", new BigDecimal("99.01"), "已创建"),
                RefundState.MESSAGES, List.of(user, assistant, response),
                RefundState.TRACE, List.of("intent", "intent")));
        var serializer = new RefundStateSerializer();
        var copy = serializer.cloneObject(state);
        assertThat(copy.order()).isEqualTo(state.order());
        assertThat(copy.command()).isEqualTo(state.command());
        assertThat(copy.policy()).isEqualTo(state.policy());
        assertThat(copy.refund()).isEqualTo(state.refund());
        assertThat(copy.messages().get(0)).isInstanceOf(UserMessage.class);
        assertThat(((AssistantMessage) copy.messages().get(1)).getToolCalls()).containsExactly(call);
        assertThat(((ToolResponseMessage) copy.messages().get(2)).getResponses()).isEqualTo(response.getResponses());
        assertThat(copy.trace()).containsExactly("intent", "intent");
        assertThat(copy).isNotSameAs(state);
    }

    @Test
    void unregisteredCheckpointTypeIsRejected() {
        assertThatThrownBy(() -> new RefundStateSerializer().readDataFromString(
                "{\"order\":{\"@type\":\"unregistered-type\"}}"))
                .hasStackTraceContaining("Type not found: unregistered-type");
    }
}
