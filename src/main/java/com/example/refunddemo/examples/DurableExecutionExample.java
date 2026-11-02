package com.example.refunddemo.examples;

import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphInput;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.action.InterruptibleAction;
import org.bsc.langgraph4j.action.InterruptionMetadata;
import org.bsc.langgraph4j.action.NodeActionWithConfig;
import org.bsc.langgraph4j.checkpoint.FileSystemSaver;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.bsc.langgraph4j.GraphDefinition.END;
import static org.bsc.langgraph4j.GraphDefinition.START;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;
import static org.bsc.langgraph4j.action.AsyncNodeActionWithConfig.node_async;

/** 教程第 6 讲：草稿落盘 → 等待审批 → 新进程恢复 → 幂等发布。 */
public final class DurableExecutionExample {
    private DurableExecutionExample() {
    }

    @FunctionalInterface
    public interface Publisher {
        String publish(String requestId, String content) throws Exception;
    }

    public static CompiledGraph<LearningState> build(ChatClient client, Path checkpointDirectory,
                                                   Publisher publisher) throws Exception {
        var workflow = LearningState.graph()
                .addNode("draft", node_async(state -> {
                    UserMessage request = new UserMessage("请写一个关于「" + state.topic() + "」的简短笑话。");
                    var answer = Objects.requireNonNull(client.prompt().messages(request)
                            .call().chatResponse()).getResult().getOutput();
                    return Map.of(LearningState.JOKE, Objects.requireNonNull(answer.getText()),
                            LearningState.MESSAGES_STATE, List.<Message>of(request, answer),
                            LearningState.STATUS, "AWAITING_APPROVAL", LearningState.TRACE, List.of("draft"));
                }))
                // node_async 保留 ReviewGate 的 InterruptibleAction 能力，执行前先检查是否暂停。
                .addNode("review", node_async(new ReviewGate()))
                .addNode("publish", node_async(state -> Map.of(
                        LearningState.RECEIPT, publisher.publish(state.<String>value(LearningState.REQUEST_ID).orElseThrow(),
                                state.joke()),
                        LearningState.STATUS, "PUBLISHED", LearningState.TRACE, List.of("publish"))))
                .addNode("reject", node_async(state -> Map.of(
                        LearningState.STATUS, "REJECTED", LearningState.TRACE, List.of("reject"))))
                .addEdge(START, "draft")
                .addEdge("draft", "review")
                .addConditionalEdges("review", state -> CompletableFuture.completedFuture(
                                state.<Boolean>value(LearningState.APPROVED).orElse(false) ? "yes" : "no"),
                        Map.of("yes", "publish", "no", "reject"))
                .addEdge("publish", END)
                .addEdge("reject", END);
        // 新进程需重建相同图，并使用相同目录、状态序列化协议和 threadId 才能恢复。
        return workflow.compile(CompileConfig.builder()
                .checkpointSaver(new FileSystemSaver(checkpointDirectory, workflow.getStateSerializer()))
                .releaseThread(false).build());
    }

    public static LearningState start(CompiledGraph<LearningState> graph, String threadId, String topic) {
        RunnableConfig config = config(threadId);
        if (graph.stateOf(config).isPresent()) {
            throw new IllegalStateException("Thread already exists; resume it or choose a new threadId");
        }
        // 教学中用 threadId 同时作为发布幂等键；真实业务可使用独立的发布申请编号。
        return graph.invoke(GraphInput.args(Map.of(LearningState.TOPIC, topic,
                LearningState.REQUEST_ID, threadId)), config).orElseThrow();
    }

    public static LearningState approve(CompiledGraph<LearningState> graph, String threadId, boolean approved)
            throws Exception {
        RunnableConfig config = config(threadId);
        var snapshot = graph.getState(config);
        if (!"review".equals(snapshot.nextNodeId())) {
            throw new IllegalStateException("Thread is not awaiting approval; next node: " + snapshot.nextNodeId());
        }
        // false 是明确拒绝，字段缺失才是“尚未审批”，二者不能混为一谈。
        // asNode="review" 会直接计算 review 的条件边，选中 publish 或 reject；不会调用 apply。
        RunnableConfig updated = graph.updateState(config, Map.of(LearningState.APPROVED, approved), "review");
        // updateState 只保存决定，不执行后续节点；resume 才继续处理。
        return graph.invoke(GraphInput.resume(), updated).orElseThrow();
    }

    /** 只恢复已批准但尚未完成的 publish，复用已保存的草稿和审批决定。 */
    public static LearningState retry(CompiledGraph<LearningState> graph, String threadId) {
        RunnableConfig config = config(threadId);
        var snapshot = graph.getState(config);
        if (!"publish".equals(snapshot.nextNodeId())
                || !snapshot.state().<Boolean>value(LearningState.APPROVED).orElse(false)) {
            throw new IllegalStateException("Only an approved, unfinished publication can be retried");
        }
        return graph.invoke(GraphInput.resume(), config).orElseThrow();
    }

    public static RunnableConfig config(String threadId) {
        if (threadId == null || !threadId.matches("[A-Za-z0-9_-]{1,80}")) {
            throw new IllegalArgumentException("threadId must contain 1-80 ASCII letters, digits, _ or -");
        }
        return RunnableConfig.builder().threadId(threadId).build();
    }

    /** 本地发布 mock：一个 requestId 对应一份正文，重试时复用同一文件路径作为收据。 */
    public static Publisher filePublisher(Path directory) {
        return (requestId, content) -> {
            config(requestId);
            Files.createDirectories(directory);
            Path receipt = directory.resolve(requestId + ".txt");
            try {
                // CREATE_NEW 避免覆盖已有发布；仅靠 checkpoint 无法避免副作用在故障后重做。
                Files.writeString(receipt, content, StandardOpenOption.CREATE_NEW);
            }
            catch (FileAlreadyExistsException ex) {
                // 相同键必须对应相同内容，否则把“键被误复用”误认为成功会掩盖数据错误。
                if (!Files.readString(receipt).equals(content)) {
                    throw new IOException("Idempotency key already belongs to different content", ex);
                }
            }
            return receipt.toAbsolutePath().toString();
        };
    }

    private static final class ReviewGate implements NodeActionWithConfig<LearningState>, InterruptibleAction<LearningState> {
        @Override
        public Map<String, Object> apply(LearningState state, RunnableConfig config) {
            // 此节点只充当审批门；具体发布在下游节点，不能在这里或 interrupt 中提前执行。
            return Map.of();
        }

        @Override
        public Optional<InterruptionMetadata<LearningState>> interrupt(String nodeId, LearningState state,
                                                                      RunnableConfig config) {
            // 检查的是“字段存在”，所以 approved=false 会放行到拒绝分支，而不是再次等待。
            return state.value(LearningState.APPROVED).isPresent() ? Optional.empty()
                    : Optional.of(InterruptionMetadata.builder(nodeId, state)
                    .reason("确认笑话草稿后再发布；使用同一 threadId 恢复。")
                    .putMetadata("draft", state.joke()).build());
        }
    }
}
