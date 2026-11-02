package com.example.refunddemo.refund.nodes;

import com.example.refunddemo.refund.schema.RefundContext;
import com.example.refunddemo.refund.schema.RefundState;
import org.bsc.langgraph4j.RunnableConfig;

import java.util.Map;

public final class LookupOrderNode implements RefundNode {
    @Override
    public Map<String, Object> execute(RefundState state, RefundContext context, RunnableConfig config) throws Exception {
        var result = context.tools().lookup(config.threadId().orElseThrow(), state.command().orElseThrow().orderId());
        return Map.of(RefundState.ORDER, result.value(), RefundState.MESSAGES, result.messages());
    }
}
