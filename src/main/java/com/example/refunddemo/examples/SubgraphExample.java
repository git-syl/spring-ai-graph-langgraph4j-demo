package com.example.refunddemo.examples;

import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphInput;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.spring.ai.serializer.jackson.SpringAIJacksonStateSerializer;
import org.bsc.langgraph4j.state.AgentState;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

import static org.bsc.langgraph4j.GraphDefinition.END;
import static org.bsc.langgraph4j.GraphDefinition.START;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

/** 教程第 8 讲：同 schema 直接嵌入子图，不同 schema 通过父节点显式映射输入输出。 */
public final class SubgraphExample {
    private SubgraphExample() {
    }

    public static CompiledGraph<LearningState> sharedState(ChatClient client) throws Exception {
        // 子图本身也能独立运行；父子图使用相同字段语义和 channel 合并规则。
        var child = LearningState.graph()
                .addNode("child_writer", node_async(state -> {
                    UserMessage request = new UserMessage("写一个关于「" + state.topic() + "」的简短笑话。");
                    var answer = Objects.requireNonNull(client.prompt().messages(request)
                            .call().chatResponse()).getResult().getOutput();
                    return Map.of(LearningState.JOKE, Objects.requireNonNull(answer.getText()),
                            LearningState.MESSAGES_STATE, List.<Message>of(request, answer),
                            LearningState.TRACE, List.of("child_writer"));
                }))
                .addEdge(START, "child_writer").addEdge("child_writer", END).compile();

        return LearningState.graph()
                .addNode("prepare", node_async(state -> Map.of(LearningState.TRACE, List.of("prepare"))))
                // 直接注册 CompiledGraph，由框架处理嵌套输出和状态合并。
                .addNode("writing_subgraph", child)
                .addNode("finish", node_async(state -> Map.of(
                        LearningState.STATUS, "COMPLETED", LearningState.TRACE, List.of("finish"))))
                .addEdge(START, "prepare").addEdge("prepare", "writing_subgraph")
                .addEdge("writing_subgraph", "finish").addEdge("finish", END).compile();
    }

    public static CompiledGraph<LearningState> mappedState(ChatClient client) throws Exception {
        // 子图只认识 subject/text，完全不知道父图的 topic/joke/trace。
        var child = new StateGraph<WriterState>(new SpringAIJacksonStateSerializer<>(WriterState::new))
                .addNode("private_writer", node_async(state -> Map.of(
                        "text", Objects.requireNonNull(client.prompt()
                                .user("写一个关于「" + state.subject() + "」的简短笑话。")
                                .call().content()))))
                .addEdge(START, "private_writer").addEdge("private_writer", END).compile();

        return LearningState.graph()
                .addNode("invoke_writer", (state, config) -> {
                    // topic -> subject 是输入适配；这是普通阻塞 invoke，内部流不会自动转发。
                    // 本例无 saver，因此使用空配置；需持久化时应另行设计父子 threadId 的关联。
                    WriterState result = child.invoke(GraphInput.args(Map.of("subject", state.topic())), RunnableConfig.empty())
                            .orElseThrow();
                    // text -> joke 是输出适配；只返回白名单字段，子图私有字段不进入父状态。
                    return CompletableFuture.completedFuture(Map.of(
                            LearningState.JOKE, result.text(), LearningState.STATUS, "COMPLETED",
                            LearningState.TRACE, List.of("invoke_writer")));
                })
                .addEdge(START, "invoke_writer").addEdge("invoke_writer", END).compile();
    }

    public static final class WriterState extends AgentState {
        public WriterState(Map<String, Object> data) {
            super(data);
        }

        public String subject() {
            return this.<String>value("subject").orElseThrow();
        }

        public String text() {
            return this.<String>value("text").orElseThrow();
        }
    }
}
