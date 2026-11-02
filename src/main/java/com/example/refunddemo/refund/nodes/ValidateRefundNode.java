package com.example.refunddemo.refund.nodes;

import com.example.refunddemo.refund.schema.RefundCommand;
import com.example.refunddemo.refund.schema.RefundContext;
import com.example.refunddemo.refund.schema.RefundState;
import com.example.refunddemo.refund.schema.RefundStatus;
import org.bsc.langgraph4j.RunnableConfig;

import java.util.Map;

/** Revalidate typed input at the graph boundary; no second model call is needed. */
public final class ValidateRefundNode implements RefundNode {
    @Override
    public Map<String, Object> execute(RefundState state, RefundContext context, RunnableConfig config) {
        var command = state.command().orElseThrow();
        return Map.of(RefundState.COMMAND, new RefundCommand(command.orderId(), command.reason()),
                RefundState.STATUS, RefundStatus.RUNNING.name());
    }
}
