package com.example.refunddemo.web;

import com.example.refunddemo.assistant.AssistantEvent;
import com.example.refunddemo.assistant.ChatAssistant;
import com.example.refunddemo.assistant.ChatTurnResult;
import com.example.refunddemo.refund.RefundWorkflow;
import com.example.refunddemo.refund.schema.RefundResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;

@RestController
public final class ChatController {
    private final ChatAssistant assistant;
    private final RefundWorkflow workflow;
    private final Executor executor;

    public ChatController(ChatAssistant assistant, RefundWorkflow workflow, @Qualifier("refundEventExecutor") Executor executor) {
        this.assistant = assistant;
        this.workflow = workflow;
        this.executor = executor;
    }

    @PostMapping("/api/chat")
    public ChatTurnResult chat(@Valid @RequestBody ChatRequest request) {
        return assistant.chat(request.conversationId(), request.requestId(), request.message(), false, event -> { });
    }

    @PostMapping(value = "/api/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@Valid @RequestBody ChatRequest request) {
        return events(writer -> assistant.chat(request.conversationId(), request.requestId(), request.message(), true, writer));
    }

    @PostMapping("/api/chat/{conversationId}/refunds/{threadId}/approval")
    public ChatTurnResult approve(@PathVariable String conversationId, @PathVariable String threadId,
                                  @Valid @RequestBody ApprovalRequest request) {
        return assistant.approve(conversationId, request.requestId(), threadId, request.approved(), event -> { });
    }

    @PostMapping(value = "/api/chat/{conversationId}/refunds/{threadId}/approval/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter approveStream(@PathVariable String conversationId, @PathVariable String threadId,
                                     @Valid @RequestBody ApprovalRequest request) {
        return events(writer -> assistant.approve(conversationId, request.requestId(), threadId, request.approved(), writer));
    }

    @GetMapping("/api/chat/{conversationId}/refunds")
    public List<RefundResult> refunds(@PathVariable String conversationId) { return assistant.refunds(conversationId); }

    @GetMapping(value = "/api/refunds/graph", produces = MediaType.TEXT_PLAIN_VALUE)
    public String diagram() { return workflow.diagram(); }

    private SseEmitter events(Function<Consumer<AssistantEvent>, ChatTurnResult> invocation) {
        var emitter = new SseEmitter(240_000L);
        var disconnected = new AtomicBoolean();
        emitter.onCompletion(() -> disconnected.set(true));
        emitter.onTimeout(() -> { disconnected.set(true); emitter.complete(); });
        emitter.onError(error -> disconnected.set(true));
        // 网络发送只是观察行为；断线后停止推送，已经发生的业务操作不因此回滚。
        Consumer<AssistantEvent> writer = event -> {
            if (disconnected.get()) return;
            try { emitter.send(SseEmitter.event().name(event.type()).data(event.data(), MediaType.APPLICATION_JSON)); }
            catch (IOException | IllegalStateException error) { disconnected.set(true); }
        };
        // 返回 emitter 后，在执行器中进行模型和图调用，片段与业务事件共用同一 writer。
        executor.execute(() -> {
            try { writer.accept(new AssistantEvent("result", invocation.apply(writer))); }
            catch (RuntimeException error) { writer.accept(new AssistantEvent("error", RefundErrorHandler.problem(error))); }
            finally { emitter.complete(); }
        });
        return emitter;
    }

    public record ChatRequest(@Pattern(regexp = "[A-Za-z0-9_-]{1,80}") String conversationId,
                              @Pattern(regexp = "[A-Za-z0-9_-]{1,80}") String requestId,
                              @NotBlank @Size(max = 4000) String message) { }
    public record ApprovalRequest(@Pattern(regexp = "[A-Za-z0-9_-]{1,80}") String requestId,
                                  @NotNull Boolean approved) { }
}
