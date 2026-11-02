package com.example.refunddemo.examples;

import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.prebuilt.MessagesState;
import org.bsc.langgraph4j.spring.ai.serializer.jackson.SpringAIJacksonStateSerializer;
import org.bsc.langgraph4j.state.Channel;
import org.bsc.langgraph4j.state.Channels;
import org.springframework.ai.chat.messages.Message;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 五个学习示例共享的状态。底层是 Map，这个类用常量和 getter 统一字段名与读取方式。
 * 节点返回的 Map 只是本次更新：普通字段覆盖，messages 和 trace 按 SCHEMA 追加。
 * 注意：继承 MessagesState 方便读取消息，不会自动替你把消息发送给模型。
 */
public final class LearningState extends MessagesState<Message> {
    public static final String TOPIC = "topic";
    public static final String JOKE = "joke";
    public static final String TRACE = "trace";
    public static final String ATTEMPTS = "attempts";
    public static final String APPROVED = "approved";
    public static final String STATUS = "status";
    public static final String REQUEST_ID = "requestId";
    public static final String RECEIPT = "receipt";

    // Channel 决定“旧值 + 本次更新”如何合并，不是字段类型的完整校验规则。
    // 保留重复项才能记录两次 write_joke；节点只返回新增消息/轨迹，不能返回整份旧历史。
    // ArrayList::new 是为各份状态创建新列表的工厂，避免不同执行共享一个可变列表。
    public static final Map<String, Channel<?>> SCHEMA = Map.of(
            MESSAGES_STATE, Channels.appenderWithDuplicate(ArrayList::new),
            TRACE, Channels.appenderWithDuplicate(ArrayList::new));

    public LearningState(Map<String, Object> data) {
        super(data);
    }

    public String topic() {
        return this.<String>value(TOPIC).orElse("");
    }

    public String joke() {
        return this.<String>value(JOKE).orElse("");
    }

    public int attempts() {
        // JSON 恢复后的具体数字类型可能不同，按 Number 读取再转成 int。
        return this.<Number>value(ATTEMPTS).map(Number::intValue).orElse(0);
    }

    public List<String> trace() {
        return this.<List<String>>value(TRACE).orElseGet(List::of);
    }

    public String status() {
        return this.<String>value(STATUS).orElse("");
    }

    /** 配置 Spring AI Message 的序列化；内存状态克隆和磁盘恢复都可能用到它。 */
    public static StateGraph<LearningState> graph() {
        return new StateGraph<>(SCHEMA, new SpringAIJacksonStateSerializer<>(LearningState::new));
    }
}
