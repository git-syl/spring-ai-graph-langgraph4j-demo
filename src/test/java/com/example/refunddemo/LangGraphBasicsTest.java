package com.example.refunddemo;

import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphInput;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.state.AgentState;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.bsc.langgraph4j.GraphDefinition.END;
import static org.bsc.langgraph4j.GraphDefinition.START;

class LangGraphBasicsTest {

    @Test
    void nodeReturnsAPartialStateUpdate() throws Exception {
        CompiledGraph<DemoState> graph = new StateGraph<DemoState>(DemoState::new)
                .addNode("normalize", (state, config) -> CompletableFuture.completedFuture(Map.of(
                        DemoState.NORMALIZED_QUERY, state.query().strip().toLowerCase())))
                .addEdge(START, "normalize")
                .addEdge("normalize", END)
                .compile();

        DemoState result = graph.invoke(
                        GraphInput.args(Map.of(DemoState.QUERY, "  HELLO  ")),
                        RunnableConfig.empty())
                .orElseThrow();

        assertThat(result.query()).isEqualTo("  HELLO  ");
        assertThat(result.normalizedQuery()).isEqualTo("hello");
    }

    @Test
    void conditionalEdgeRoutesFromUpdatedState() throws Exception {
        CompiledGraph<DemoState> graph = new StateGraph<DemoState>(DemoState::new)
                .addNode("policy", (state, config) -> CompletableFuture.completedFuture(Map.of(
                        DemoState.ELIGIBLE, state.query().contains("allow"))))
                .addNode("accept", (state, config) -> CompletableFuture.completedFuture(Map.of(
                        DemoState.RESULT, "ACCEPTED")))
                .addNode("reject", (state, config) -> CompletableFuture.completedFuture(Map.of(
                        DemoState.RESULT, "REJECTED")))
                .addEdge(START, "policy")
                .addConditionalEdges("policy",
                        state -> CompletableFuture.completedFuture(
                                state.eligible() ? "yes" : "no"),
                        Map.of("yes", "accept", "no", "reject"))
                .addEdge("accept", END)
                .addEdge("reject", END)
                .compile();

        DemoState accepted = graph.invoke(
                GraphInput.args(Map.of(DemoState.QUERY, "allow refund")),
                RunnableConfig.empty()).orElseThrow();
        DemoState rejected = graph.invoke(
                GraphInput.args(Map.of(DemoState.QUERY, "deny refund")),
                RunnableConfig.empty()).orElseThrow();

        assertThat(accepted.result()).isEqualTo("ACCEPTED");
        assertThat(rejected.result()).isEqualTo("REJECTED");
    }

    private static final class DemoState extends AgentState {

        static final String QUERY = "query";
        static final String NORMALIZED_QUERY = "normalizedQuery";
        static final String ELIGIBLE = "eligible";
        static final String RESULT = "result";

        DemoState(Map<String, Object> initData) {
            super(initData);
        }

        String query() {
            return this.<String>value(QUERY).orElse("");
        }

        String normalizedQuery() {
            return this.<String>value(NORMALIZED_QUERY).orElse("");
        }

        boolean eligible() {
            return this.<Boolean>value(ELIGIBLE).orElse(false);
        }

        String result() {
            return this.<String>value(RESULT).orElse("");
        }
    }
}
