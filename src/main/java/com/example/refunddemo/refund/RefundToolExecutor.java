package com.example.refunddemo.refund;

import com.example.refunddemo.refund.schema.Order;
import com.example.refunddemo.refund.schema.RefundExecution;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.bsc.langgraph4j.spring.ai.tool.SpringAIToolService;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.tool.ToolCallback;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** 图内受控调用订单/支付工具；工具名、业务参数和授权上下文均由服务端路径确定。 */
public final class RefundToolExecutor {
    private final SpringAIToolService service;
    private final ObjectMapper json;

    public RefundToolExecutor(List<ToolCallback> callbacks, ObjectMapper json) {
        this.service = new SpringAIToolService(List.copyOf(callbacks));
        this.json = Objects.requireNonNull(json);
    }

    public Result<Order> lookup(String threadId, String orderId) throws Exception {
        Result<Order> result = invoke("get_order", Map.of("orderId", orderId), Order.class,
                Map.of("threadId", threadId));
        if (!orderId.equals(result.value().orderId())) {
            throw new IllegalStateException("Order tool returned a different order");
        }
        return result;
    }

    public Result<RefundExecution> refund(String threadId, String orderId, BigDecimal amount, String reason) throws Exception {
        // refundAuthorized 属于可信 ToolContext，不属于模型可生成的业务 JSON 参数。
        Result<RefundExecution> result = invoke("create_refund", Map.of("orderId", orderId, "amount", amount, "reason", reason),
                RefundExecution.class, Map.of("threadId", threadId, "refundAuthorized", true));
        var value = result.value();
        if (!List.of("SUCCESS", "ALREADY_REFUNDED").contains(value.status())
                || value.refundId() == null || value.refundId().isBlank() || value.amount() == null
                || value.amount().compareTo(amount) != 0) {
            throw new IllegalStateException("Refund tool did not confirm the requested refund");
        }
        return result;
    }

    private <T> Result<T> invoke(String name, Map<String, Object> arguments, Class<T> type,
                                 Map<String, Object> context) throws Exception {
        // 复用 Spring AI 工具回调协议，主动构造 ToolCall；这里没有再次询问模型选哪个工具。
        var call = new AssistantMessage.ToolCall("call_" + UUID.randomUUID(), "function", name, json.writeValueAsString(arguments));
        var result = service.executeFunctions(List.of(call), context).join();
        if (result.toolResponses().size() != 1 || !name.equals(result.toolResponses().getFirst().name())) {
            throw new IllegalStateException("Unexpected tool response");
        }
        // 将工具 JSON 还原为明确 DTO，并保留配对的调用/结果消息用于状态记录。
        T value = Objects.requireNonNull(json.readValue(result.toolResponses().getFirst().responseData(), type));
        return new Result<>(value, List.of(AssistantMessage.builder().toolCalls(List.of(call)).build(),
                ToolResponseMessage.builder().responses(result.toolResponses()).build()));
    }

    public record Result<T>(T value, List<Message> messages) {
        public Result { messages = List.copyOf(messages); }
    }
}
