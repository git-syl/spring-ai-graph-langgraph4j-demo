package com.example.refunddemo.examples;

import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.spring.ai.generators.StreamingChatGenerator;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.bsc.langgraph4j.GraphDefinition.END;
import static org.bsc.langgraph4j.GraphDefinition.START;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

/** 教程第 7 讲：在一个图流中观察普通节点输出与 Spring AI 模型文本片段。 */
public final class StreamingExample {
    private StreamingExample() {
    }

    public static CompiledGraph<LearningState> build(ChatClient client) throws Exception {
        return LearningState.graph()
                .addNode("refine_topic", node_async(state -> Map.of(
                        LearningState.TOPIC, state.topic().strip() + "与猫",
                        LearningState.TRACE, List.of("refine_topic"))))
                .addNode("stream_joke", node_async(state -> {
                    UserMessage request = new UserMessage("写一个关于「" + state.topic() + "」的简短笑话。");
                    // 保留 Flux<ChatResponse>，不在节点内 block 成完整正文。
                    var responses = client.prompt().messages(request).stream().chatResponse();
                    var generator = StreamingChatGenerator.<LearningState>builder()
                            .startingNode("stream_joke")
                            .startingState(state)
                            .mapResult(response -> {
                                // 这是流正常结束后的聚合响应，不是单个片段；完整消息只追加一次。
                                var answer = response.getResult().getOutput();
                                return Map.of(LearningState.JOKE, Objects.requireNonNull(answer.getText()),
                                        LearningState.MESSAGES_STATE, List.<Message>of(request, answer),
                                        LearningState.TRACE, List.of("stream_joke"));
                            })
                            .build(responses);
                    // LangGraph4j consumes the generator and applies mapResult once at stream completion.
                    // 暂时交回嵌入式 generator，让图消费并转发 StreamingOutput。
                    // 消费结束后 mapResult 的更新才成为最终状态，此时 joke 是 String。
                    return Map.of(LearningState.JOKE, generator);
                }))
                .addEdge(START, "refine_topic")
                .addEdge("refine_topic", "stream_joke")
                .addEdge("stream_joke", END)
                .compile();
    }
}
