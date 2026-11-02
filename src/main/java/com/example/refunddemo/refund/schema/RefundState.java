package com.example.refunddemo.refund.schema;

import org.bsc.langgraph4j.state.AgentState;
import org.bsc.langgraph4j.state.Channel;
import org.bsc.langgraph4j.state.Channels;
import org.springframework.ai.chat.messages.Message;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 可恢复的业务数据。工具客户端、Clock、回调和事件 writer 放在 RefundContext。 */
public final class RefundState extends AgentState {
    public static final String MESSAGES = "messages";
    public static final String COMMAND = "command";
    public static final String ORDER = "order";
    public static final String POLICY = "policy";
    public static final String APPROVAL = "approval";
    public static final String REFUND = "refund";
    public static final String ANSWER = "answer";
    public static final String STATUS = "status";
    public static final String TRACE = "trace";

    // 消息和执行轨迹累加，保留重复步骤；command/order/policy 等普通字段默认覆盖。
    public static final Map<String, Channel<?>> SCHEMA = Map.of(
            MESSAGES, Channels.appenderWithDuplicate(ArrayList::new),
            TRACE, Channels.appenderWithDuplicate(ArrayList::new));

    public RefundState(Map<String, Object> data) {
        super(data);
    }

    public List<Message> messages() { return this.<List<Message>>value(MESSAGES).orElseGet(List::of); }
    public Optional<RefundCommand> command() { return value(COMMAND); }
    public Optional<Order> order() { return value(ORDER); }
    public Optional<PolicyDecision> policy() { return value(POLICY); }
    public Optional<RefundExecution> refund() { return value(REFUND); }
    public String answer() { return this.<String>value(ANSWER).orElse(""); }
    public List<String> trace() { return this.<List<String>>value(TRACE).orElseGet(List::of); }
    public RefundStatus status() { return RefundStatus.valueOf(this.<String>value(STATUS).orElse("RUNNING")); }
    public Optional<ApprovalDecision> approval() { return this.<String>value(APPROVAL).map(ApprovalDecision::valueOf); }
}
