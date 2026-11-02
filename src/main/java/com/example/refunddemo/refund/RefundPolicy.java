package com.example.refunddemo.refund;

import com.example.refunddemo.refund.schema.Order;
import com.example.refunddemo.refund.schema.PolicyDecision;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

/** Deterministic business policy; no LLM, IO or wall-clock access. */
public record RefundPolicy(Duration refundWindow, BigDecimal approvalThreshold) {
    public RefundPolicy {
        if (refundWindow == null || refundWindow.isNegative() || refundWindow.isZero()
                || approvalThreshold == null || approvalThreshold.signum() <= 0) {
            throw new IllegalArgumentException("Refund window and approval threshold must be positive");
        }
    }

    public PolicyDecision evaluate(Order order, Instant now) {
        if (!"PAID".equals(order.status())) {
            return new PolicyDecision(false, false, "订单当前状态不可退款：" + order.status(), BigDecimal.ZERO);
        }
        if (order.paidAmount().signum() <= 0 || order.deliveredAt().isAfter(now)) {
            return new PolicyDecision(false, false, "订单金额或签收时间无效", BigDecimal.ZERO);
        }
        if (Duration.between(order.deliveredAt(), now).compareTo(refundWindow) > 0) {
            return new PolicyDecision(false, false, "签收已超过退款期限", BigDecimal.ZERO);
        }
        boolean manual = order.chargebackRisk() || order.paidAmount().compareTo(approvalThreshold) >= 0;
        return new PolicyDecision(true, manual, manual ? "订单需要人工审批" : "订单符合自动退款规则", order.paidAmount());
    }
}
