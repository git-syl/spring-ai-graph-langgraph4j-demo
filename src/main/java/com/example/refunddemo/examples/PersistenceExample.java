package com.example.refunddemo.examples;

import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphInput;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.checkpoint.MemorySaver;
import org.bsc.langgraph4j.checkpoint.Checkpoint;
import org.bsc.langgraph4j.state.StateSnapshot;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.bsc.langgraph4j.GraphDefinition.END;
import static org.bsc.langgraph4j.GraphDefinition.START;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

/** 教程第 5 讲：内存 checkpoint、历史查询，以及保留原流程的分支重放。 */
public final class PersistenceExample {
    private PersistenceExample() {
    }

    public record Session(CompiledGraph<LearningState> graph, MemorySaver saver) {
    }

    public static Session create(ChatClient client) throws Exception {
        // 每次 create 都是一个全新的内存存储；进程重启或换 saver 后找不到先前历史。
        // 此处 Session 只是图与 saver 的组合，与 Spring AI 的聊天 Session 没有关系。
        MemorySaver saver = new MemorySaver();
        var graph = LearningState.graph()
                .addNode("generate_topic", node_async(state -> Map.of(
                        LearningState.TOPIC, Objects.requireNonNull(client.prompt()
                                .user("帮我生成一个笑话主题，只返回主题。").call().content()),
                        LearningState.TRACE, List.of("generate_topic"))))
                .addNode("write_joke", node_async(state -> {
                    UserMessage request = new UserMessage("请写一个关于「" + state.topic() + "」的简短笑话。");
                    var answer = Objects.requireNonNull(client.prompt().messages(request)
                            .call().chatResponse()).getResult().getOutput();
                    return Map.of(LearningState.JOKE, Objects.requireNonNull(answer.getText()),
                            LearningState.MESSAGES_STATE, List.<Message>of(request, answer),
                            LearningState.TRACE, List.of("write_joke"));
                }))
                .addEdge(START, "generate_topic")
                .addEdge("generate_topic", "write_joke")
                .addEdge("write_joke", END)
                // 1.9.7 源码默认 releaseThread=true；显式保留，END 后才能继续查看活动历史。
                .compile(CompileConfig.builder().checkpointSaver(saver)
                        .releaseThread(false).build());
        return new Session(graph, saver);
    }

    public static LearningState replayWithTopic(Session session, String branchThreadId,
                                               StateSnapshot<LearningState> selected, String newTopic) throws Exception {
        if (!"generate_topic".equals(selected.node())) {
            throw new IllegalArgumentException("Select the checkpoint after generate_topic");
        }
        var graph = session.graph();
        RunnableConfig branch = RunnableConfig.builder().threadId(branchThreadId).build();
        if (graph.stateOf(branch).isPresent()) {
            throw new IllegalArgumentException("Use a new threadId for the replay branch");
        }
        // 第一步：复制选中快照到新 thread。1.9.7 的 updateState 会更新选中槽位，
        // 直接在原 thread 上修改历史会影响原记录，因此这里先隔离分支。
        RunnableConfig historical = session.saver().put(branch, Checkpoint.builder()
                .state(selected.state().data()).nodeId(selected.node()).nextNodeId(selected.nextNodeId()).build());
        // 第二步：仅修改主题，保留其他字段。asNode 表示“视为 generate_topic 已完成”，
        // 框架计算它的后继 write_joke，并把恢复位置放入返回的 RunnableConfig。
        RunnableConfig edited = graph.updateState(historical, Map.of(LearningState.TOPIC, newTopic), "generate_topic");
        // 第三步：使用返回配置恢复；不会重新调用 generate_topic，写作模型会再次调用。
        return graph.invoke(GraphInput.resume(), edited).orElseThrow();
    }
}
