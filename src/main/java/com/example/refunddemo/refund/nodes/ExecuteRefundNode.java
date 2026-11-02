package com.example.refunddemo.refund.nodes;

import com.example.refunddemo.refund.schema.ApprovalDecision;
import com.example.refunddemo.refund.schema.RefundContext;
import com.example.refunddemo.refund.schema.RefundState;
import org.bsc.langgraph4j.RunnableConfig;

import java.util.Map;

/** 执行前复核服务端授权与金额，再通过图内受控工具请求退款。 */
public final class ExecuteRefundNode implements RefundNode {
    @Override
    public Map<String, Object> execute(RefundState state, RefundContext context, RunnableConfig config) throws Exception {
        var command = state.command().orElseThrow();
        var order = state.order().orElseThrow();
        var policy = state.policy().orElseThrow();
        // 不能仅凭“路由走到了这里”就付款：还需核实资格、明确审批、订单和金额一致。
        // evaluate 使用已有订单快照和当前时间；接入真实系统时还需刷新外部订单/支付事实。
        if (!policy.eligible()
                || (policy.needsApproval() && state.approval().orElse(null) != ApprovalDecision.APPROVED)
                || !command.orderId().equals(order.orderId())
                || policy.refundableAmount().compareTo(order.paidAmount()) != 0
                || !context.policy().evaluate(order, context.clock().instant()).eligible()) {
            throw new IllegalStateException("Refund execution reached without a valid authorization");
        }
        // 参数来自已校验的业务数据，threadId 由服务端配置提供，不读取模型给出的付款参数。
        var result = context.tools().refund(config.threadId().orElseThrow(), command.orderId(),
                policy.refundableAmount(), command.reason());
        return Map.of(RefundState.REFUND, result.value(), RefundState.MESSAGES, result.messages());
    }
}
