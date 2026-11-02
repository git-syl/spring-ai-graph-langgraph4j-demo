package com.example.refunddemo.refund.schema;

import java.util.List;

/** Stable API result; business facts remain typed instead of being reconstructed from model prose. */
public record RefundResult(String threadId, RefundStatus status, String answer, boolean approvalRequired,
                           RefundCommand command, Order order, PolicyDecision policy, RefundExecution refund,
                           List<String> trace) {
    public RefundResult {
        trace = List.copyOf(trace);
    }

    public static RefundResult from(String threadId, RefundState state) {
        return new RefundResult(threadId, state.status(), state.answer(),
                state.status() == RefundStatus.APPROVAL_REQUIRED, state.command().orElse(null),
                state.order().orElse(null), state.policy().orElse(null), state.refund().orElse(null), state.trace());
    }
}
