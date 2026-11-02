package com.example.refunddemo.web;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "spring.ai.deepseek.api-key=test-only")
@Import(AssistantHttpTest.ModelConfiguration.class)
class AssistantHttpTest extends AssistantHttpContract {
    @Test
    void inventedOrderAndProviderFailureHaveNoBusinessEffect() throws Exception {
        String id = id();
        assertThat(post("/api/chat", Map.of("conversationId", id, "message", "invent-order")).statusCode()).isEqualTo(502);
        assertThat(json.readTree(get("/api/chat/" + id + "/refunds").body()).isEmpty()).isTrue();
        var failed = post("/api/chat", Map.of("message", "provider-failure"));
        assertThat(failed.statusCode()).isEqualTo(502);
        assertThat(failed.body()).doesNotContain("private-provider-detail");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ModelConfiguration {
        @Bean @Primary ChatModel deterministicModel() { return new NativeToolModel(); }
    }

    /** Emits actual AssistantMessage.ToolCall objects. Spring AI, not the fake, executes the tools. */
    static class NativeToolModel implements ChatModel {
        @Override public org.springframework.ai.chat.prompt.ChatOptions getOptions() {
            return org.springframework.ai.model.tool.ToolCallingChatOptions.builder().build();
        }

        @Override public ChatResponse call(Prompt prompt) {
            var messages = prompt.getInstructions();
            if (messages.getLast() instanceof ToolResponseMessage response) return text("工具处理结果：" + response.getResponses().getFirst().responseData());
            var users = messages.stream().filter(UserMessage.class::isInstance).map(message -> message.getText()).toList();
            String query = users.getLast();
            String history = String.join("\n", users);
            if (query.equals("provider-failure")) throw new IllegalStateException("private-provider-detail");
            if (query.equals("invent-order")) return tool("start_refund_workflow", "O-1001");
            if (query.contains("代号是什么")) return text(history.contains("蓝鲸42") ? "你的代号是蓝鲸42。" : "你还没有告诉我代号。");
            if (query.contains("你好")) return text("你好，很高兴为你服务。");
            if (query.contains("只咨询")) return text("签收 30 天内且为已支付订单可申请，达到 300 元或存在风险需审批。");
            if (query.contains("同意退款")) return text("请使用当前退款卡片上的审批操作确认。");
            var order = Pattern.compile("O-[0-9]+").matcher(query);
            if (!order.find()) return text("请提供订单号和退款原因。");
            return tool(query.contains("进度") ? "get_refund_status" : "start_refund_workflow", order.group());
        }

        @Override public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.defer(() -> {
                var response = call(prompt);
                if (response.hasToolCalls()) return Flux.just(response);
                String text = response.getResult().getOutput().getText();
                int middle = text.length() / 2;
                return Flux.just(text(text.substring(0, middle)), text(text.substring(middle)));
            });
        }

        private static ChatResponse tool(String name, String order) {
            String arguments = "{\"orderId\":\"" + order + "\"" + (name.equals("start_refund_workflow") ? ",\"reason\":\"质量问题\"" : "") + "}";
            return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("")
                    .toolCalls(List.of(new AssistantMessage.ToolCall("call-" + UUID.randomUUID(), "function", name, arguments))).build())));
        }
        private static ChatResponse text(String text) { return new ChatResponse(List.of(new Generation(new AssistantMessage(text)))); }
    }
}
