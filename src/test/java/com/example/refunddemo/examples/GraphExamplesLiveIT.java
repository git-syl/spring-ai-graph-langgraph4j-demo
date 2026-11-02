package com.example.refunddemo.examples;

import org.bsc.langgraph4j.GraphInput;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.streaming.StreamingOutput;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.client.ChatClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Opt-in real provider acceptance: mvn -Plive-graph verify. Keys come only from environment. */
class GraphExamplesLiveIT {
    static ChatClient client;

    @TempDir
    Path directory;

    @BeforeAll
    static void realProvider() {
        client = GraphExamples.client(false);
    }

    @Test
    void graphApiWithRealModel() throws Exception {
        var graph = GraphApiExample.build(client, 120);
        var state = graph.invoke(GraphInput.args(Map.of(LearningState.TOPIC, "程序员与猫")), RunnableConfig.empty()).orElseThrow();
        assertThat(state.topic()).isNotBlank();
        assertRealText(state.joke());
        assertThat(state.attempts()).isBetween(1, 2);
        assertThat(state.messages()).hasSize(state.attempts() * 2);
        assertThat(state.trace()).startsWith("refine_topic", "write_joke").endsWith("finish");
        System.out.printf("LIVE Graph API: attempts=%d answerCharacters=%d status=%s%n",
                state.attempts(), state.joke().length(), state.status());
    }

    @Test
    void checkpointHistoryAndEditedReplayWithRealModel() throws Exception {
        var session = PersistenceExample.create(client);
        var graph = session.graph();
        var config = RunnableConfig.builder().threadId("live-history").build();
        var original = graph.invoke(GraphInput.noArgs(), config).orElseThrow();
        var history = graph.getStateHistory(config);
        var selected = history.stream().filter(s -> s.node().equals("generate_topic")).findFirst().orElseThrow();
        var edited = PersistenceExample.replayWithTopic(session, "live-edited", selected, "学习飞行的小鸟");
        assertRealText(original.joke());
        assertRealText(edited.joke());
        assertThat(edited.topic()).isEqualTo("学习飞行的小鸟");
        assertThat(graph.getState(config).state().joke()).isEqualTo(original.joke());
        assertThat(edited.trace()).containsExactly("generate_topic", "write_joke");
        System.out.printf("LIVE Persistence: checkpoints=%d originalAndBranchRetained=true%n", history.size());
    }

    @Test
    void diskCheckpointResumesAndPublishesRealDraft() throws Exception {
        var publisher = DurableExecutionExample.filePublisher(directory.resolve("publications"));
        var graph = DurableExecutionExample.build(client, directory, publisher);
        var waiting = DurableExecutionExample.start(graph, "live-durable", "不想上班的猫");
        assertThat(waiting.status()).isEqualTo("AWAITING_APPROVAL");
        assertRealText(waiting.joke());
        assertThat(Files.exists(directory.resolve("publications/live-durable.txt"))).isFalse();

        var reloaded = DurableExecutionExample.build(client, directory, publisher);
        var completed = DurableExecutionExample.approve(reloaded, "live-durable", true);
        assertThat(completed.status()).isEqualTo("PUBLISHED");
        assertThat(completed.joke()).isEqualTo(waiting.joke());
        assertThat(Files.readString(directory.resolve("publications/live-durable.txt"))).isEqualTo(waiting.joke());
        System.out.printf("LIVE Durable: diskReload=true draftCharacters=%d published=true%n", waiting.joke().length());
    }

    @Test
    void realProviderChunksMatchFinalAccumulatedAnswer() throws Exception {
        var graph = StreamingExample.build(client);
        var outputs = graph.stream(GraphInput.args(Map.of(LearningState.TOPIC, "冰淇淋")), RunnableConfig.empty())
                .stream().toList();
        var chunks = outputs.stream().filter(output -> output instanceof StreamingOutput<?>)
                .map(output -> ((StreamingOutput<?>) output).chunk()).toList();
        assertThat(chunks.stream().filter(chunk -> !chunk.isEmpty()).count()).isGreaterThan(1);
        var state = outputs.getLast().state();
        assertRealText(state.joke());
        assertThat(state.joke()).isEqualTo(String.join("", chunks));
        assertThat(state.messages().getLast().getText()).isEqualTo(state.joke());
        System.out.printf("LIVE Streaming: chunks=%d answerCharacters=%d accumulatedMatches=true%n",
                chunks.size(), state.joke().length());
    }

    @Test
    void bothSubgraphStylesUseRealModel() throws Exception {
        var input = GraphInput.args(Map.of(LearningState.TOPIC, "程序员"));
        var shared = SubgraphExample.sharedState(client).invoke(input, RunnableConfig.empty()).orElseThrow();
        var mapped = SubgraphExample.mappedState(client).invoke(input, RunnableConfig.empty()).orElseThrow();
        assertRealText(shared.joke());
        assertRealText(mapped.joke());
        assertThat(shared.trace()).containsExactly("prepare", "child_writer", "finish");
        assertThat(mapped.data()).doesNotContainKeys("subject", "text");
        System.out.printf("LIVE Subgraphs: sharedCharacters=%d mappedCharacters=%d%n",
                shared.joke().length(), mapped.joke().length());
    }

    private static void assertRealText(String text) {
        assertThat(text).isNotBlank().doesNotContain("【离线演示");
    }
}
