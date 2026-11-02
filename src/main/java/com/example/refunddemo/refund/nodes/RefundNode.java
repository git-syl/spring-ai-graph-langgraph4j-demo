package com.example.refunddemo.refund.nodes;

import com.example.refunddemo.refund.schema.RefundContext;
import com.example.refunddemo.refund.schema.RefundState;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.action.NodeActionWithConfig;

import java.util.Map;

/** Java equivalent of a Python node receiving (state, runtime.context). */
public interface RefundNode extends NodeActionWithConfig<RefundState> {
    @Override
    default Map<String, Object> apply(RefundState state, RunnableConfig config) throws Exception {
        return execute(state, RefundContext.from(config), config);
    }

    Map<String, Object> execute(RefundState state, RefundContext context, RunnableConfig config) throws Exception;
}
