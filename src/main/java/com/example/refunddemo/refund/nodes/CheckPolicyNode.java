package com.example.refunddemo.refund.nodes;

import com.example.refunddemo.refund.schema.RefundContext;
import com.example.refunddemo.refund.schema.RefundState;
import com.example.refunddemo.refund.schema.RefundStatus;
import org.bsc.langgraph4j.RunnableConfig;

import java.util.Map;

public final class CheckPolicyNode implements RefundNode {
    @Override
    public Map<String, Object> execute(RefundState state, RefundContext context, RunnableConfig config) {
        var policy = context.policy().evaluate(state.order().orElseThrow(), context.clock().instant());
        var status = !policy.eligible() ? RefundStatus.REJECTED
                : policy.needsApproval() ? RefundStatus.APPROVAL_REQUIRED : RefundStatus.RUNNING;
        return Map.of(RefundState.POLICY, policy,
                RefundState.STATUS, status.name(),
                RefundState.ANSWER, policy.needsApproval() ? "此订单需要人工审批，确认后才会执行退款。" : "");
    }
}
