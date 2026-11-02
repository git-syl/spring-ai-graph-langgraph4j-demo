package com.example.refunddemo.refund.nodes;

import com.example.refunddemo.refund.schema.ApprovalDecision;
import com.example.refunddemo.refund.schema.RefundContext;
import com.example.refunddemo.refund.schema.RefundState;
import com.example.refunddemo.refund.schema.RefundStatus;
import org.bsc.langgraph4j.RunnableConfig;
import org.springframework.ai.chat.messages.AssistantMessage;

import java.util.List;
import java.util.Map;

/** Financial outcomes are rendered from confirmed facts, keeping model prose out of execution results. */
public final class AnswerNode implements RefundNode {
    @Override
    public Map<String, Object> execute(RefundState state, RefundContext context, RunnableConfig config) {
        RefundStatus status;
        String answer;
        if (state.refund().isPresent()) {
            var refund = state.refund().orElseThrow();
            status = "ALREADY_REFUNDED".equals(refund.status()) ? RefundStatus.ALREADY_REFUNDED : RefundStatus.COMPLETED;
            answer = "退款单号：" + refund.refundId() + "；金额：" + refund.amount().toPlainString() + "。";
        }
        else if (state.approval().orElse(null) == ApprovalDecision.DECLINED) {
            status = RefundStatus.DECLINED;
            answer = "你已拒绝退款操作，本次未执行退款。";
        }
        else if (state.order().filter(order -> "REFUNDED".equals(order.status())).isPresent()) {
            status = RefundStatus.ALREADY_REFUNDED;
            answer = "订单已退款，本次未重复创建退款单。";
        }
        else if (state.policy().isPresent()) {
            status = RefundStatus.REJECTED;
            answer = state.policy().orElseThrow().reason() + "，本次未执行退款。";
        }
        else {
            status = RefundStatus.REJECTED;
            answer = state.answer();
        }
        return Map.of(RefundState.STATUS, status.name(), RefundState.ANSWER, answer,
                RefundState.MESSAGES, List.of(new AssistantMessage(answer)));
    }
}
