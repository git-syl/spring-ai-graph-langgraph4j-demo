package com.example.refunddemo.examples;

import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphInput;
import org.bsc.langgraph4j.GraphRepresentation;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.streaming.StreamingOutput;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.deepseek.api.DeepSeekApi;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.client.reactive.JdkClientHttpConnector;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 五个示例的 CLI 入口；手动创建 ChatClient，不启动 Spring Boot、HTTP 服务或数据库。 */
public final class GraphExamples {
    private static final Set<String> COMMANDS = Set.of("all", "graph-api", "persistence", "durable-start",
            "durable-resume", "durable-retry", "streaming", "subgraphs");

    private GraphExamples() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0 || Arrays.asList(args).contains("--help")) {
            System.out.println("Usage: GraphExamples <" + String.join("|", COMMANDS) + "> [--offline]"
                    + " [--thread=demo-1] [--topic=冰淇淋] [--approved=true|false]"
                    + " [--checkpoint-dir=.graph-checkpoints] [--fail-publish]");
            return;
        }
        String command = args[0];
        if (!COMMANDS.contains(command)) {
            throw new IllegalArgumentException("Unknown example: " + command);
        }
        for (int i = 1; i < args.length; i++) {
            if (!args[i].equals("--offline") && !args[i].equals("--fail-publish")
                    && !args[i].startsWith("--thread=") && !args[i].startsWith("--topic=")
                    && !args[i].startsWith("--approved=") && !args[i].startsWith("--checkpoint-dir=")) {
                throw new IllegalArgumentException("Unknown option: " + args[i]);
            }
        }
        boolean offline = Arrays.asList(args).contains("--offline");
        // 离线仅替换模型提供方；后面的图、消息序列化和文件存储仍真实执行。
        ChatClient client = client(offline);
        String topic = option(args, "topic", "冰淇淋");
        if (command.equals("all") || command.equals("graph-api")) {
            var graph = GraphApiExample.build(client, 80);
            System.out.println(graph.getGraph(GraphRepresentation.Type.MERMAID).content());
            print("Graph API", graph.invoke(GraphInput.args(Map.of(LearningState.TOPIC, topic)), RunnableConfig.empty()).orElseThrow());
        }
        if (command.equals("all") || command.equals("persistence")) {
            var session = PersistenceExample.create(client);
            var graph = session.graph();
            String thread = "history-" + UUID.randomUUID();
            RunnableConfig config = RunnableConfig.builder().threadId(thread).build();
            print("Original", graph.invoke(GraphInput.noArgs(), config).orElseThrow());
            var history = graph.getStateHistory(config);
            history.forEach(snapshot -> System.out.printf("checkpoint=%s node=%s next=%s createdAt=%s%n",
                    snapshot.checkpointId(), snapshot.node(), snapshot.nextNodeId(), snapshot.createdAt()));
            // 历史按新到旧排列，按节点含义选择快照，不能猜第几个元素就是目标位置。
            var selected = history.stream().filter(s -> s.node().equals("generate_topic")).findFirst().orElseThrow();
            print("Replay in new thread with edited topic", PersistenceExample.replayWithTopic(session, thread + "-edited", selected, "小鸟"));
        }
        if (command.equals("all") || command.startsWith("durable-")) {
            Path directory = Path.of(option(args, "checkpoint-dir", ".graph-checkpoints"));
            DurableExecutionExample.Publisher publisher = Arrays.asList(args).contains("--fail-publish")
                    ? (id, text) -> { throw new IllegalStateException("模拟发布失败；运行 durable-retry 恢复"); }
                    : DurableExecutionExample.filePublisher(directory.resolve("publications"));
            var graph = DurableExecutionExample.build(client, directory, publisher);
            String thread = option(args, "thread", command.equals("all") ? "demo-" + UUID.randomUUID() : "demo-1");
            System.out.println("Durable threadId=" + thread);
            switch (command) {
                case "all" -> {
                    print("Waiting for approval", DurableExecutionExample.start(graph, thread, topic));
                    // 重建图与 saver，验证不依赖原对象内存；单独两次 CLI 可进一步验证跨进程。
                    var reloaded = DurableExecutionExample.build(client, directory, publisher);
                    print("Approved", DurableExecutionExample.approve(reloaded, thread, true));
                }
                case "durable-start" -> print("Waiting for approval", DurableExecutionExample.start(graph, thread, topic));
                case "durable-resume" -> {
                    String approval = option(args, "approved", "");
                    if (!approval.equals("true") && !approval.equals("false")) {
                        throw new IllegalArgumentException("Provide --approved=true or --approved=false");
                    }
                    print("Decision recorded", DurableExecutionExample.approve(graph, thread, Boolean.parseBoolean(approval)));
                }
                case "durable-retry" -> print("Publication retried", DurableExecutionExample.retry(graph, thread));
                default -> throw new IllegalStateException(command);
            }
        }
        if (command.equals("all") || command.equals("streaming")) {
            streaming(StreamingExample.build(client), topic);
        }
        if (command.equals("all") || command.equals("subgraphs")) {
            var shared = SubgraphExample.sharedState(client);
            for (var output : shared.stream(GraphInput.args(Map.of(LearningState.TOPIC, topic)), RunnableConfig.empty())) {
                System.out.println("[shared child] " + output.node() + " trace=" + output.state().trace());
            }
            print("Mapped child", SubgraphExample.mappedState(client)
                    .invoke(GraphInput.args(Map.of(LearningState.TOPIC, topic)), RunnableConfig.empty()).orElseThrow());
        }
    }

    static ChatClient client(boolean offline) {
        if (offline) {
            System.out.println("OFFLINE: 使用固定文本验证图和 Spring AI 协议；不代表真实模型调用。");
            return ChatClient.create(new OfflineChatModel());
        }
        String apiKey = System.getenv("DEEPSEEK_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("Set DEEPSEEK_API_KEY to use DeepSeek, or explicitly pass --offline");
        }
        // Use system DNS consistently for blocking calls and SSE; model calls can exceed default read timeouts.
        var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30))
                .version(HttpClient.Version.HTTP_1_1).build();
        var requests = new JdkClientHttpRequestFactory(http);
        requests.setReadTimeout(Duration.ofMinutes(2));
        var api = DeepSeekApi.builder().apiKey(apiKey)
                .baseUrl(System.getenv().getOrDefault("DEEPSEEK_BASE_URL", "https://ark.cn-beijing.volces.com/api/v3"))
                .restClientBuilder(RestClient.builder().requestFactory(requests))
                .webClientBuilder(WebClient.builder().clientConnector(new JdkClientHttpConnector(http))).build();
        var model = DeepSeekChatModel.builder().deepSeekApi(api)
                .options(DeepSeekChatOptions.builder()
                        .model(System.getenv().getOrDefault("DEEPSEEK_MODEL", "deepseek-v4-flash-ga-260731"))
                        .disableThinking().maxTokens(1024).build()).build();
        return ChatClient.create(model);
    }

    private static void streaming(CompiledGraph<LearningState> graph, String topic) {
        for (var output : graph.stream(GraphInput.args(Map.of(LearningState.TOPIC, topic)), RunnableConfig.empty())) {
            if (output instanceof StreamingOutput<?> token) {
                // 模型片段用于即时显示；完整答案稍后从普通节点输出的 State 中读取。
                System.out.print(token.chunk());
                System.out.flush();
            }
            else {
                System.out.println("\n[node] " + output.node() + " trace=" + output.state().trace());
                if (!output.state().joke().isEmpty()) {
                    System.out.println("[accumulated answer] " + output.state().joke());
                }
            }
        }
    }

    private static void print(String title, LearningState state) {
        System.out.printf("%n[%s] status=%s topic=%s%n%s%ntrace=%s%n", title, state.status(), state.topic(),
                state.joke(), state.trace());
    }

    private static String option(String[] args, String key, String fallback) {
        return Arrays.stream(args).filter(arg -> arg.startsWith("--" + key + "="))
                .map(arg -> arg.substring(key.length() + 3)).findFirst().orElse(fallback);
    }
}
