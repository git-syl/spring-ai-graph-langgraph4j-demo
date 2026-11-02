package com.example.refunddemo.refund;

import com.example.refunddemo.refund.graph.RefundGraph;
import com.example.refunddemo.refund.schema.ApprovalDecision;
import com.example.refunddemo.refund.schema.RefundCommand;
import com.example.refunddemo.refund.schema.RefundContext;
import com.example.refunddemo.refund.schema.RefundResult;
import com.example.refunddemo.refund.schema.RefundState;
import com.example.refunddemo.refund.schema.RefundStatus;
import com.example.refunddemo.refund.schema.StepInfo;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphInput;
import org.bsc.langgraph4j.GraphRepresentation;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/** 管理 start/approve/get 和调用生命周期；节点、拓扑及 HTTP 协议在各自类中定义。 */
public final class RefundWorkflow {
    private final RefundContext dependencies;
    private final CompiledGraph<RefundState> graph;
    // 固定数量的锁条带保护同一流程的“读状态 → 更新 → 恢复”；仅在当前 JVM 内有效。
    private final ReentrantLock[] locks = new ReentrantLock[64];

    public RefundWorkflow(RefundContext dependencies, BaseCheckpointSaver saver) throws Exception {
        this.dependencies = dependencies;
        this.graph = RefundGraph.compile(saver);
        Arrays.setAll(locks, index -> new ReentrantLock());
    }

    public RefundResult start(String threadId, RefundCommand command) {
        return start(threadId, command, event -> { });
    }

    public RefundResult start(String threadId, RefundCommand command, Consumer<StepInfo> writer) {
        String id = threadId == null || threadId.isBlank() ? UUID.randomUUID().toString() : threadId;
        validateId(id);
        java.util.Objects.requireNonNull(command, "command");
        RunnableConfig config = config(id, writer);
        ReentrantLock lock = lock(id);
        lock.lock();
        try {
            if (graph.stateOf(config).isPresent()) {
                throw new RefundWorkflowException(RefundWorkflowException.Kind.CONFLICT, "threadId 已存在，请恢复审批或使用新 ID");
            }
            // 新执行只接收结构化命令，金额、审批决定不能由外部输入随意写入 State。
            return execute(GraphInput.args(Map.of(RefundState.COMMAND, command)), config);
        }
        finally { lock.unlock(); }
    }

    public RefundResult approve(String threadId, boolean approved) {
        return approve(threadId, approved, event -> { });
    }

    public RefundResult approve(String threadId, boolean approved, Consumer<StepInfo> writer) {
        validateId(threadId);
        RunnableConfig config = config(threadId, writer);
        ReentrantLock lock = lock(threadId);
        lock.lock();
        try {
            // 检查实际恢复位置、业务状态与决定是否已记录；不能只根据前端按钮是否显示判断。
            var snapshot = graph.stateOf(config).orElseThrow(() -> notFound(threadId));
            if (!"approval_gate".equals(snapshot.nextNodeId())
                    || snapshot.state().status() != RefundStatus.APPROVAL_REQUIRED || snapshot.state().approval().isPresent()) {
                throw new RefundWorkflowException(RefundWorkflowException.Kind.CONFLICT, "该线程不在等待审批，不能重复或更改审批决定");
            }
            RunnableConfig updated;
            try {
                // asNode 表示“视为 approval_gate 已完成”，据更新后的状态计算 execute/answer。
                // 门节点动作因此不会再执行；显式追加 trace，保持审批路径的业务轨迹完整。
                updated = graph.updateState(config, Map.of(RefundState.APPROVAL,
                        (approved ? ApprovalDecision.APPROVED : ApprovalDecision.DECLINED).name(),
                        RefundState.TRACE, List.of("approval_gate")), "approval_gate");
            }
            catch (Exception ex) {
                throw new RefundWorkflowException(RefundWorkflowException.Kind.FAILED, "审批决定保存失败", ex);
            }
            // 保存决定与继续执行是两步；用返回配置恢复原图，不重新 start 或重新调用模型。
            return execute(GraphInput.resume(), updated);
        }
        finally { lock.unlock(); }
    }

    public RefundResult get(String threadId) {
        validateId(threadId);
        ReentrantLock lock = lock(threadId);
        lock.lock();
        try {
            return RefundResult.from(threadId, graph.stateOf(config(threadId, event -> { }))
                    .orElseThrow(() -> notFound(threadId)).state());
        }
        finally { lock.unlock(); }
    }

    public String diagram() {
        return graph.getGraph(GraphRepresentation.Type.MERMAID).content();
    }

    private RefundResult execute(GraphInput input, RunnableConfig config) {
        var context = RefundContext.from(config);
        try {
            RefundState state = graph.invoke(input, config).orElseThrow(() -> new IllegalStateException("No graph state"));
            RefundResult result = RefundResult.from(config.threadId().orElseThrow(), state);
            // finish=true 表示本次调用结束；WAITING 仍然是尚未完成的业务流程。
            context.emit(config, "workflow", result.approvalRequired() ? StepInfo.Status.WAITING : StepInfo.Status.SUCCESS,
                    Map.of("status", result.status().name()), true);
            return result;
        }
        catch (RuntimeException ex) {
            context.emit(config, "workflow", StepInfo.Status.ERROR, Map.of("message", "退款流程执行失败"), true);
            throw new RefundWorkflowException(RefundWorkflowException.Kind.FAILED, "退款流程执行失败", ex);
        }
    }

    private RunnableConfig config(String threadId, Consumer<StepInfo> writer) {
        // 每次调用重新注入依赖和事件接收方；这些对象不会作为业务 State 写入 checkpoint。
        return RunnableConfig.builder().threadId(threadId)
                .putMetadata(RefundContext.CONFIG_KEY, dependencies.withWriter(writer)).build();
    }

    private ReentrantLock lock(String id) {
        return locks[Math.floorMod(id.hashCode(), locks.length)];
    }

    private static void validateId(String id) {
        if (id == null || !id.matches("[A-Za-z0-9_-]{1,80}")) {
            throw new IllegalArgumentException("threadId must contain 1-80 ASCII letters, digits, _ or -");
        }
    }

    private static RefundWorkflowException notFound(String id) {
        return new RefundWorkflowException(RefundWorkflowException.Kind.NOT_FOUND, "未找到退款线程：" + id);
    }
}
