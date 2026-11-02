package com.example.refunddemo.assistant;

import org.springframework.ai.chat.model.ToolContext;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Trusted per-request data; never supplied in the model's JSON arguments. */
public final class AssistantToolContext {
    public static final String KEY = "assistant.runtime";
    final String conversationId;
    final ConversationRegistry.Conversation conversation;
    private final Consumer<AssistantEvent> writer;
    private final List<String> toolsCalled = new ArrayList<>();

    public AssistantToolContext(String conversationId, ConversationRegistry.Conversation conversation,
                                Consumer<AssistantEvent> writer) {
        this.conversationId = conversationId;
        this.conversation = conversation;
        this.writer = writer;
    }

    static AssistantToolContext from(ToolContext context) {
        if (context.getContext().get(KEY) instanceof AssistantToolContext runtime) return runtime;
        throw new IllegalStateException("Missing server-side assistant context");
    }

    synchronized void recordTool(String name) { toolsCalled.add(name); }
    synchronized List<String> toolsCalled() { return List.copyOf(toolsCalled); }

    public void emit(String type, Object value) {
        try { writer.accept(new AssistantEvent(type, value)); }
        catch (RuntimeException ignored) {
            // UI disconnection must not change the outcome of an already executed business operation.
            org.slf4j.LoggerFactory.getLogger(AssistantToolContext.class).debug("Assistant observer disconnected");
        }
    }
}
