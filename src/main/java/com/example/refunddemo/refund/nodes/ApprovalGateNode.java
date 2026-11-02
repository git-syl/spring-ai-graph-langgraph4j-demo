package com.example.refunddemo.refund.nodes;

import com.example.refunddemo.refund.schema.RefundContext;
import com.example.refunddemo.refund.schema.RefundState;
import com.example.refunddemo.refund.schema.StepInfo;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.action.InterruptibleAction;
import org.bsc.langgraph4j.action.InterruptionMetadata;

import java.util.Map;
import java.util.Optional;

/** 在节点执行前检查审批条件；支付只在后续 execute_refund 中执行。 */
public final class ApprovalGateNode implements RefundNode, InterruptibleAction<RefundState> {
    @Override
    public Map<String, Object> execute(RefundState state, RefundContext context, RunnableConfig config) {
        // 门节点不修改业务字段，后面的条件边决定继续退款还是生成拒绝结果。
        return Map.of();
    }

    @Override
    public Optional<InterruptionMetadata<RefundState>> interrupt(String nodeId, RefundState state, RunnableConfig config) {
        // 无需审批或已经有明确决定时放行；只有“需要但尚未决定”才暂停。
        if (state.policy().orElseThrow().needsApproval() && state.approval().isEmpty()) {
            var data = Map.<String, Object>of("orderId", state.command().orElseThrow().orderId(),
                    "amount", state.policy().orElseThrow().refundableAmount());
            RefundContext.from(config).emit(config, "approval_gate", StepInfo.Status.WAITING, data, false);
            return Optional.of(InterruptionMetadata.builder(nodeId, state).reason("确认是否执行退款").build());
        }
        return Optional.empty();
    }
}
