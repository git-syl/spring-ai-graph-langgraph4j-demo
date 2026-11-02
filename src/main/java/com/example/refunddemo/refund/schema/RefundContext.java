package com.example.refunddemo.refund.schema;

import com.example.refunddemo.refund.RefundPolicy;
import com.example.refunddemo.refund.RefundToolExecutor;
import org.bsc.langgraph4j.RunnableConfig;

import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/** 每次调用的工具、规则、时钟和事件 writer；恢复时重新注入，不写进业务 checkpoint。 */
public record RefundContext(RefundToolExecutor tools, RefundPolicy policy, Clock clock, Consumer<StepInfo> stepWriter) {
    public static final String CONFIG_KEY = "refund.context";

    public RefundContext {
        Objects.requireNonNull(tools);
        Objects.requireNonNull(policy);
        Objects.requireNonNull(clock);
        Objects.requireNonNull(stepWriter);
    }

    public RefundContext withWriter(Consumer<StepInfo> writer) {
        return new RefundContext(tools, policy, clock, writer);
    }

    public static RefundContext from(RunnableConfig config) {
        return config.metadata(CONFIG_KEY).filter(RefundContext.class::isInstance).map(RefundContext.class::cast)
                .orElseThrow(() -> new IllegalStateException("Missing RefundContext in RunnableConfig"));
    }

    public void emit(RunnableConfig config, String step, StepInfo.Status status, Map<String, Object> data, boolean finish) {
        try {
            stepWriter.accept(new StepInfo(config.threadId().orElseThrow(), step, status, data, finish, clock.instant()));
        }
        catch (RuntimeException ex) {
            // SSE 断线等观察者异常不能把已成功的退款改成节点失败，进而触发业务重做。
            org.slf4j.LoggerFactory.getLogger(RefundContext.class).warn("Step observer failed for step {}", step);
        }
    }
}
