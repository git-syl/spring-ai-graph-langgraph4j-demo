package com.example.refunddemo.examples;

import org.bsc.langgraph4j.GraphInput;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.streaming.StreamingOutput;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GraphExamplesTest {
    @TempDir
    Path directory;

    @Test
    void graphApiUsesPartialUpdatesAndAppendsConversationWhileRewriting() throws Exception {
        var model = new ScriptedModel("猫", "这是一个超过五个字的笑话", "猫睡了");
        var graph = GraphApiExample.build(ChatClient.create(model), 5);
        var state = graph.invoke(GraphInput.args(Map.of(LearningState.TOPIC, "动物")), RunnableConfig.empty()).orElseThrow();

        assertThat(state.topic()).isEqualTo("猫");
        assertThat(state.joke()).isEqualTo("猫睡了");
        assertThat(state.attempts()).isEqualTo(2);
        assertThat(state.status()).isEqualTo("ACCEPTED");
        assertThat(state.trace()).containsExactly("refine_topic", "write_joke", "write_joke", "finish");
        assertThat(state.messages()).hasSize(4);
        assertThat(model.prompts.getLast().getInstructions())
                .anyMatch(message -> "这是一个超过五个字的笑话".equals(message.getText()));
    }

    @Test
    void graphApiStopsAfterTwoAttemptsEvenWhenModelIgnoresLengthLimit() throws Exception {
        var graph = GraphApiExample.build(ChatClient.create(new ScriptedModel("猫", "太长了", "仍然太长")), 1);
        var state = graph.invoke(GraphInput.args(Map.of(LearningState.TOPIC, "动物")), RunnableConfig.empty()).orElseThrow();
        assertThat(state.status()).isEqualTo("LIMIT_REACHED");
        assertThat(state.attempts()).isEqualTo(2);
    }

    @Test
    void persistenceSelectsHistoryEditsTopicAndReplaysWithoutRegeneratingIt() throws Exception {
        var model = new ScriptedModel("猫", "猫笑话", "鸟笑话", "狗", "狗笑话");
        var session = PersistenceExample.create(ChatClient.create(model));
        var graph = session.graph();
        var config = RunnableConfig.builder().threadId("one").build();
        graph.invoke(GraphInput.noArgs(), config).orElseThrow();
        var selected = graph.getStateHistory(config).stream()
                .filter(snapshot -> snapshot.node().equals("generate_topic")).findFirst().orElseThrow();
        assertThat(selected.nextNodeId()).isEqualTo("write_joke");
        assertThat(selected.createdAt()).isNotNull();

        var edited = PersistenceExample.replayWithTopic(session, "one-edited", selected, "鸟");
        assertThat(edited.topic()).isEqualTo("鸟");
        assertThat(edited.joke()).isEqualTo("鸟笑话");
        assertThat(edited.trace()).containsExactly("generate_topic", "write_joke");
        assertThat(model.prompts).hasSize(3);
        var historical = graph.getState(RunnableConfig.builder(config).checkPointId(selected.checkpointId()).build());
        assertThat(historical.state().topic()).isEqualTo("猫");
        assertThat(historical.state().joke()).isEmpty();

        var other = graph.invoke(GraphInput.noArgs(), RunnableConfig.builder().threadId("two").build()).orElseThrow();
        assertThat(other.topic()).isEqualTo("狗");
        assertThat(graph.getState(config).state().topic()).isEqualTo("猫");
        assertThat(other.messages()).hasSize(2);
    }

    @Test
    void durableReloadRestoresSpringMessagesAndApprovesWithoutCallingModelAgain() throws Exception {
        var model = new ScriptedModel("保存到磁盘的笑话");
        var client = ChatClient.create(model);
        var calls = new AtomicInteger();
        DurableExecutionExample.Publisher publisher = (id, text) -> "receipt-" + calls.incrementAndGet();
        var original = DurableExecutionExample.build(client, directory, publisher);
        var waiting = DurableExecutionExample.start(original, "one", "猫");
        assertThat(waiting.status()).isEqualTo("AWAITING_APPROVAL");
        assertThat(calls).hasValue(0);

        var reloaded = DurableExecutionExample.build(client, directory, publisher);
        var snapshot = reloaded.getState(DurableExecutionExample.config("one"));
        assertThat(snapshot.nextNodeId()).isEqualTo("review");
        assertThat(snapshot.state().messages().getFirst()).isInstanceOf(UserMessage.class);
        assertThat(snapshot.state().messages().getLast()).isInstanceOf(AssistantMessage.class);
        assertThat(snapshot.state().messages().getLast().getText()).isEqualTo("保存到磁盘的笑话");

        var approved = DurableExecutionExample.approve(reloaded, "one", true);
        assertThat(approved.status()).isEqualTo("PUBLISHED");
        assertThat(approved.trace()).containsExactly("draft", "publish");
        assertThat(calls).hasValue(1);
        assertThat(model.prompts).hasSize(1);
        assertThatThrownBy(() -> DurableExecutionExample.approve(reloaded, "one", true))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("not awaiting approval");
        assertThat(calls).hasValue(1);
    }

    @Test
    void durableRejectionNeverCallsPublisherAndThreadsRemainIsolated() throws Exception {
        var calls = new AtomicInteger();
        var graph = DurableExecutionExample.build(ChatClient.create(new ScriptedModel("猫笑话", "鸟笑话")),
                directory, (id, text) -> "receipt-" + calls.incrementAndGet());
        DurableExecutionExample.start(graph, "one", "猫");
        DurableExecutionExample.start(graph, "two", "鸟");
        assertThat(DurableExecutionExample.approve(graph, "one", false).status()).isEqualTo("REJECTED");
        assertThat(graph.getState(DurableExecutionExample.config("two")).state().status()).isEqualTo("AWAITING_APPROVAL");
        assertThat(calls).hasValue(0);
        assertThatThrownBy(() -> DurableExecutionExample.start(graph, "one", "新草稿"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("already exists");
        assertThatThrownBy(() -> DurableExecutionExample.config("../escape"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void failedPublicationCanResumeAfterReloadWithoutRedraftingOrReapproval() throws Exception {
        var model = new ScriptedModel("猫笑话");
        var client = ChatClient.create(model);
        var failing = DurableExecutionExample.build(client, directory,
                (id, text) -> { throw new IllegalStateException("publisher temporarily unavailable"); });
        DurableExecutionExample.start(failing, "retry", "猫");
        assertThatThrownBy(() -> DurableExecutionExample.approve(failing, "retry", true))
                .hasStackTraceContaining("publisher temporarily unavailable");

        var reloaded = DurableExecutionExample.build(client, directory, (id, text) -> "success");
        var state = DurableExecutionExample.retry(reloaded, "retry");
        assertThat(state.status()).isEqualTo("PUBLISHED");
        assertThat(state.trace()).containsExactly("draft", "publish");
        assertThat(model.prompts).hasSize(1);
        assertThatThrownBy(() -> DurableExecutionExample.retry(reloaded, "retry"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void publicationUsesIdempotencyKeyWhenSideEffectSucceedsButNodeFails() throws Exception {
        var client = ChatClient.create(new ScriptedModel("已发布的猫笑话"));
        Path publications = directory.resolve("publications");
        var publisher = DurableExecutionExample.filePublisher(publications);
        var first = DurableExecutionExample.build(client, directory, (id, text) -> {
            publisher.publish(id, text);
            throw new IllegalStateException("模拟副作用完成后、checkpoint 前失败");
        });
        DurableExecutionExample.start(first, "idempotent", "猫");
        assertThatThrownBy(() -> DurableExecutionExample.approve(first, "idempotent", true))
                .hasStackTraceContaining("checkpoint 前失败");

        var second = DurableExecutionExample.build(client, directory, publisher);
        assertThat(DurableExecutionExample.retry(second, "idempotent").status()).isEqualTo("PUBLISHED");
        try (var files = Files.list(publications)) {
            assertThat(files.toList()).containsExactly(publications.resolve("idempotent.txt"));
        }
        assertThat(Files.readString(publications.resolve("idempotent.txt"))).isEqualTo("已发布的猫笑话");
        assertThatThrownBy(() -> publisher.publish("idempotent", "different text"))
                .hasMessageContaining("different content");
    }

    @Test
    void streamingEmitsProviderChunksAndOnlyThenCommitsAccumulatedAnswer() throws Exception {
        var model = new ScriptedModel();
        var graph = StreamingExample.build(ChatClient.create(model));
        var outputs = graph.stream(GraphInput.args(Map.of(LearningState.TOPIC, "冰淇淋")), RunnableConfig.empty())
                .stream().toList();
        var chunks = outputs.stream().filter(output -> output instanceof StreamingOutput<?>)
                .map(output -> ((StreamingOutput<?>) output).chunk()).toList();
        assertThat(chunks).containsExactly("猫", "喜欢", "冰淇淋");
        var state = outputs.getLast().state();
        assertThat(state.joke()).isEqualTo(String.join("", chunks));
        assertThat(state.messages().getLast().getText()).isEqualTo("猫喜欢冰淇淋");
        assertThat(state.trace()).containsExactly("refine_topic", "stream_joke");
        assertThat(model.streamCalls).isEqualTo(1);
        assertThat(model.prompts).isEmpty();
    }

    @Test
    void streamingPropagatesProviderErrorsInsteadOfReturningPartialSuccess() throws Exception {
        ChatModel model = new ScriptedModel() {
            @Override
            public Flux<ChatResponse> stream(Prompt prompt) {
                return Flux.concat(Flux.just(response("部分文本")), Flux.error(new IllegalStateException("provider disconnected")));
            }
        };
        var graph = StreamingExample.build(ChatClient.create(model));
        assertThatThrownBy(() -> graph.stream(GraphInput.args(Map.of(LearningState.TOPIC, "猫")), RunnableConfig.empty())
                .stream().toList()).hasStackTraceContaining("provider disconnected");
    }

    @Test
    void sharedChildAppendsItsMessagesAndTraceExactlyOnce() throws Exception {
        var graph = SubgraphExample.sharedState(ChatClient.create(new ScriptedModel("猫笑话")));
        var state = graph.invoke(GraphInput.args(Map.of(LearningState.TOPIC, "猫",
                LearningState.MESSAGES_STATE, List.of(new UserMessage("已有消息")))), RunnableConfig.empty()).orElseThrow();
        assertThat(state.joke()).isEqualTo("猫笑话");
        assertThat(state.trace()).containsExactly("prepare", "child_writer", "finish");
        assertThat(state.messages()).hasSize(3);
        assertThat(state.messages().getFirst().getText()).isEqualTo("已有消息");
    }

    @Test
    void adapterMapsDifferentSchemasWithoutLeakingChildPrivateState() throws Exception {
        var model = new ScriptedModel("鸟笑话");
        var graph = SubgraphExample.mappedState(ChatClient.create(model));
        var state = graph.invoke(GraphInput.args(Map.of(LearningState.TOPIC, "小鸟")), RunnableConfig.empty()).orElseThrow();
        assertThat(state.joke()).isEqualTo("鸟笑话");
        assertThat(state.topic()).isEqualTo("小鸟");
        assertThat(state.data()).doesNotContainKeys("subject", "text");
        assertThat(model.prompts.getFirst().getUserMessage().getText()).contains("小鸟");
    }

    /** Exercise the real ChatClient and message serializer, replacing only the external provider. */
    private static class ScriptedModel implements ChatModel {
        final ArrayDeque<String> answers;
        final List<Prompt> prompts = new ArrayList<>();
        int streamCalls;

        ScriptedModel(String... answers) {
            this.answers = new ArrayDeque<>(List.of(answers));
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            prompts.add(prompt);
            return response(answers.removeFirst());
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            streamCalls++;
            return Flux.just("猫", "喜欢", "冰淇淋").map(ScriptedModel::response);
        }

        static ChatResponse response(String text) {
            return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
        }
    }
}
