package com.example.refunddemo.examples;

import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.CompiledGraph;
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

/** 教程 4.3：细化主题 → 写笑话 → Java 判断是否重写 → 输出结果。 */
public final class GraphApiExample {
    private GraphApiExample() {
    }

    public static CompiledGraph<LearningState> build(ChatClient client, int maxCharacters) throws Exception {
        if (maxCharacters < 1) {
            throw new IllegalArgumentException("maxCharacters must be positive");
        }
        // 这里构建并编译拓扑，还没有调用模型；模型请求发生在 invoke/stream 执行节点时。
        return LearningState.graph()
                .addNode("refine_topic", node_async(state -> {
                    String topic = Objects.requireNonNull(client.prompt()
                            .system("你是笑话主题编辑。把用户主题细化为一个有趣的主题，只返回主题。")
                            .user(state.topic()).call().content());
                    // topic 覆盖原输入；trace 仅追加这一次节点名，其他字段原样保留。
                    return Map.of(LearningState.TOPIC, topic, LearningState.TRACE, List.of("refine_topic"));
                }))
                .addNode("write_joke", node_async(state -> {
                    UserMessage request = new UserMessage("写一个关于「" + state.topic() + "」的笑话，最多"
                            + maxCharacters + "字。" + (state.attempts() > 0 ? "上次过长，请压缩。" : ""));
                    // 重写时显式传入上一轮 messages；复用 ChatClient 本身不会自动记住历史。
                    var answer = Objects.requireNonNull(client.prompt()
                            .system("你是中文幽默写手。只返回笑话正文。")
                            .messages(state.messages()).messages(request).call().chatResponse())
                            .getResult().getOutput();
                    // 消息 channel 会追加，所以这里只返回本轮问题和回答，不能再次返回旧 messages。
                    return Map.of(LearningState.JOKE, Objects.requireNonNull(answer.getText()),
                            LearningState.ATTEMPTS, state.attempts() + 1,
                            LearningState.MESSAGES_STATE, List.<Message>of(request, answer),
                            LearningState.TRACE, List.of("write_joke"));
                }))
                .addNode("finish", node_async(state -> Map.of(
                        LearningState.STATUS, state.joke().length() <= maxCharacters ? "ACCEPTED" : "LIMIT_REACHED",
                        LearningState.TRACE, List.of("finish"))))
                .addEdge(START, "refine_topic")
                .addEdge("refine_topic", "write_joke")
                // 路由读取的是已合并的状态：第一次写完 attempts=1，第二次写完 attempts=2。
                // rewrite/done 是路由标签，由下方 Map 映射到节点名；业务上最多写两次。
                .addConditionalEdges("write_joke", state -> CompletableFuture.completedFuture(
                                state.joke().length() > maxCharacters && state.attempts() < 2 ? "rewrite" : "done"),
                        Map.of("rewrite", "write_joke", "done", "finish"))
                .addEdge("finish", END)
                // recursionLimit 是额外的图步骤保护，与上方“最多写两次”不是同一个计数器。
                .compile(CompileConfig.builder().recursionLimit(10).build());
    }
}
