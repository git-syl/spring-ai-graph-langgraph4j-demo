package com.example.refunddemo.refund.graph;

import com.example.refunddemo.refund.nodes.AnswerNode;
import com.example.refunddemo.refund.nodes.ApprovalGateNode;
import com.example.refunddemo.refund.nodes.CheckPolicyNode;
import com.example.refunddemo.refund.nodes.ExecuteRefundNode;
import com.example.refunddemo.refund.nodes.LookupOrderNode;
import com.example.refunddemo.refund.nodes.ValidateRefundNode;
import com.example.refunddemo.refund.schema.ApprovalDecision;
import com.example.refunddemo.refund.schema.RefundContext;
import com.example.refunddemo.refund.schema.RefundState;
import com.example.refunddemo.refund.schema.RefundStatus;
import com.example.refunddemo.refund.schema.StepInfo;
import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.bsc.langgraph4j.GraphDefinition.END;
import static org.bsc.langgraph4j.GraphDefinition.START;
import static org.bsc.langgraph4j.action.AsyncNodeActionWithConfig.node_async;

/** Composition root for the graph. Node implementations have no Spring or HTTP dependencies. */
public final class RefundGraph {
    private RefundGraph() {
    }

    public static CompiledGraph<RefundState> compile(BaseCheckpointSaver saver) throws Exception {
        return new StateGraph<>(RefundState.SCHEMA, new RefundStateSerializer())
                .addNode("validate_request", node_async(new ValidateRefundNode()))
                .addNode("lookup_order", node_async(new LookupOrderNode()))
                .addNode("policy_check", node_async(new CheckPolicyNode()))
                .addNode("approval_gate", node_async(new ApprovalGateNode()))
                .addNode("execute_refund", node_async(new ExecuteRefundNode()))
                .addNode("answer", node_async(new AnswerNode()))
                .addEdge(START, "validate_request")
                .addEdge("validate_request", "lookup_order")
                .addEdge("lookup_order", "policy_check")
                // 路由在节点更新合并后计算；不符合政策直接生成结果，不进入退款执行节点。
                .addConditionalEdges("policy_check", state -> CompletableFuture.completedFuture(
                                state.policy().orElseThrow().eligible() ? "approval" : "answer"),
                        Map.of("approval", "approval_gate", "answer", "answer"))
                .addConditionalEdges("approval_gate", state -> CompletableFuture.completedFuture(
                                !state.policy().orElseThrow().needsApproval()
                                        || state.approval().orElse(null) == ApprovalDecision.APPROVED ? "execute" : "answer"),
                        Map.of("execute", "execute_refund", "answer", "answer"))
                .addEdge("execute_refund", "answer")
                .addEdge("answer", END)
                // 统一包裹各节点：发送进度、传播执行异常，并给返回更新追加一条 trace。
                .addWrapCallNodeHook((node, state, config, action) -> {
                    var context = RefundContext.from(config);
                    context.emit(config, node, StepInfo.Status.RUNNING, Map.of(), false);
                    CompletableFuture<Map<String, Object>> result;
                    try {
                        result = action.apply(state, config);
                    }
                    catch (Exception ex) {
                        result = CompletableFuture.failedFuture(ex);
                    }
                    return result.handle((updates, error) -> {
                        if (error != null) {
                            context.emit(config, node, StepInfo.Status.ERROR, Map.of("message", "节点执行失败"), false);
                            throw new CompletionException(error);
                        }
                        // 对外事件去掉内部工具消息；SUCCESS 表示动作完成，不是支付结算或落盘确认。
                        var eventData = new HashMap<>(updates);
                        eventData.remove(RefundState.MESSAGES);
                        context.emit(config, node, StepInfo.Status.SUCCESS, eventData, false);
                        var partial = new HashMap<>(updates);
                        partial.put(RefundState.TRACE, List.of(node));
                        return partial;
                    });
                })
                .compile(CompileConfig.builder().checkpointSaver(saver).releaseThread(false).recursionLimit(20).build());
    }
}
