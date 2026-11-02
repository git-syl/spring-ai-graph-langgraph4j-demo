package com.example.refunddemo.examples;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.List;

/** Explicit opt-in fixture, for learning graph execution without a provider key or network. */
final class OfflineChatModel implements ChatModel {
    @Override
    public ChatResponse call(Prompt prompt) {
        return response("【离线演示，未调用大模型】猫问程序员：为什么总熬夜？因为白天的 bug 还没睡。");
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return Flux.just("【离线演示，未调用大模型】", "猫问程序员：", "为什么总熬夜？", "因为白天的 bug 还没睡。")
                .map(OfflineChatModel::response);
    }

    private static ChatResponse response(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }
}
