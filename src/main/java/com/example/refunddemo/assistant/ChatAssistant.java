package com.example.refunddemo.assistant;

import com.example.refunddemo.refund.RefundWorkflowException;
import com.example.refunddemo.refund.schema.RefundResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.session.advisor.SessionAdvisors;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/** 聊天主入口：Spring AI 管对话和工具循环，退款生命周期由工具适配层转交工作流。 */
public final class ChatAssistant {
    private final ChatClient client;
    private final ConversationRegistry conversations;
    private final RefundWorkflowTools tools;
    private final ObjectMapper json;
    private final String prompt;

    public ChatAssistant(ChatClient client, ConversationRegistry conversations, RefundWorkflowTools tools,
                         ObjectMapper json, String prompt) {
        this.client = client;
        this.conversations = conversations;
        this.tools = tools;
        this.json = json;
        this.prompt = prompt;
    }

    public ChatTurnResult chat(String conversationId, String requestId, String message, boolean streaming,
                               Consumer<AssistantEvent> writer) {
        if (message == null || message.isBlank() || message.length() > 4000) {
            throw new IllegalArgumentException("message 必须为 1-4000 个字符");
        }
        String id = idOrNew(conversationId);
        String request = idOrNew(requestId);
        var conversation = conversations.getOrCreate(id);
        // 同一会话的一轮消息、工具结果与请求缓存一起处理，避免并发轮次串乱历史。
        conversation.turnLock.lock();
        try {
            // 回放的是这个 requestId 当时的成功响应；查最新退款状态要发起新请求。
            var cached = cached(conversation, request, "chat:" + message);
            if (cached != null) return cached;
            // 记录用户实际提供过的订单号，工具执行前据此拒绝模型猜出的订单号。
            conversation.observeUser(message);
            var runtime = new AssistantToolContext(id, conversation, writer);
            // 审批可以在聊天之外完成，所以每轮重新查询业务事实，不能只依赖旧聊天记录。
            // tools 暴露可调用能力；toolContext 注入可信运行对象；Session ID 选择聊天历史。
            var spec = client.prompt().system(prompt)
                    .messages(new SystemMessage("以下为服务端查询的当前退款状态，金额、审批和是否完成以此为准："
                            + json.writeValueAsString(tools.results(conversation))))
                    .user(message).tools(tools).toolContext(Map.of(AssistantToolContext.KEY, runtime))
                    .advisors(SessionAdvisors.session(id));
            String answer;
            if (streaming) {
                // 到达片段立即发送；collectList 只为最后组装完整 answer，不会推迟 doOnNext。
                // 该阻塞等待运行在控制器安排的执行器中，不应照搬到 WebFlux 事件循环。
                var chunks = spec.stream().content().doOnNext(chunk -> runtime.emit("token", chunk))
                        .collectList().block(Duration.ofMinutes(3));
                answer = String.join("", java.util.Objects.requireNonNull(chunks));
            }
            else { answer = spec.call().content(); }
            if (answer == null || answer.isBlank()) throw new IllegalStateException("Model returned no answer");
            var result = new ChatTurnResult(id, request, answer, tools.results(conversation), runtime.toolsCalled());
            // 只缓存成功轮次；失败时可能已发生退款，重试仍需业务层的关联与幂等保护。
            conversation.requests.put(request, new ConversationRegistry.CompletedRequest("chat:" + message, result));
            return result;
        }
        catch (RefundWorkflowException | IllegalArgumentException error) { throw error; }
        catch (Exception error) {
            org.slf4j.LoggerFactory.getLogger(ChatAssistant.class).error("Assistant turn failed (conversation={}, request={})", id, request, error);
            throw new RefundWorkflowException(RefundWorkflowException.Kind.FAILED, "聊天请求失败，可查询当前退款状态", error);
        }
        finally { conversation.turnLock.unlock(); }
    }

    public ChatTurnResult approve(String conversationId, String requestId, String threadId, boolean approved,
                                  Consumer<AssistantEvent> writer) {
        String request = idOrNew(requestId);
        var conversation = conversations.require(conversationId);
        ConversationRegistry.validateId(threadId);
        String input = "approval:" + threadId + ":" + approved;
        conversation.turnLock.lock();
        try {
            var cached = cached(conversation, request, input);
            if (cached != null) return cached;
            var runtime = new AssistantToolContext(conversationId, conversation, writer);
            // 明确审批走确定性的业务路径，不让模型再次解释或批准这一布尔决定。
            var refund = tools.approve(runtime, threadId, approved);
            var result = new ChatTurnResult(conversationId, request, refund.answer(), tools.results(conversation), List.of());
            conversation.requests.put(request, new ConversationRegistry.CompletedRequest(input, result));
            return result;
        }
        finally { conversation.turnLock.unlock(); }
    }

    public List<RefundResult> refunds(String conversationId) {
        var conversation = conversations.require(conversationId);
        conversation.turnLock.lock();
        try { return tools.results(conversation); }
        finally { conversation.turnLock.unlock(); }
    }

    private static ChatTurnResult cached(ConversationRegistry.Conversation conversation, String requestId, String input) {
        var existing = conversation.requests.get(requestId);
        if (existing == null) return null;
        if (!existing.input().equals(input)) {
            throw new RefundWorkflowException(RefundWorkflowException.Kind.CONFLICT, "requestId 已用于不同请求");
        }
        return existing.result();
    }

    private static String idOrNew(String value) {
        String result = value == null ? UUID.randomUUID().toString() : value;
        ConversationRegistry.validateId(result);
        return result;
    }
}
