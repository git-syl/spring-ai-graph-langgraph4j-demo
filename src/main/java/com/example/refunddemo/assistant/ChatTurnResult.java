package com.example.refunddemo.assistant;

import com.example.refunddemo.refund.schema.RefundResult;
import java.util.List;

/** Model prose and authoritative workflow facts are separate fields. */
public record ChatTurnResult(String conversationId, String requestId, String answer,
                             List<RefundResult> refunds, List<String> toolsCalled) {
    public ChatTurnResult {
        refunds = List.copyOf(refunds);
        toolsCalled = List.copyOf(toolsCalled);
    }
}
