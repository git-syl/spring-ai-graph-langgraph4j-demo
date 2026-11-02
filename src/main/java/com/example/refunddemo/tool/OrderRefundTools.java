package com.example.refunddemo.tool;

import com.example.refunddemo.refund.schema.Order;
import com.example.refunddemo.refund.schema.RefundExecution;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Mock business tools. In production these methods would call order and payment
 * services; the LangGraph workflow does not need to change.
 */
@Component
public class OrderRefundTools {

    private final Clock clock;

    public OrderRefundTools(Clock clock) {
        this.clock = clock;
    }

    private final AtomicInteger refundSequence = new AtomicInteger(1000);
    private final Map<String, RefundRecord> refunds = new ConcurrentHashMap<>();

    @Tool(name = "get_order", description = "查询订单状态、支付金额、收货时间和风控标记")
    public Order getOrder(@ToolParam(description = "订单号，例如 O-1001") String orderId) {
        return switch (orderId) {
            case "O-1001" -> order(orderId, "PAID", "99.00", 5, false);
            case "O-2001" -> order(orderId, "PAID", "399.00", 3, true);
            case "O-3001" -> order(orderId, "PAID", "59.00", 45, false);
            case "O-4001" -> order(orderId, "REFUNDED", "129.00", 2, false);
            default -> new Order(orderId, "NOT_FOUND", BigDecimal.ZERO, Instant.EPOCH, false);
        };
    }

    @Tool(name = "create_refund", description = "创建订单退款。只有策略节点判定可退款且审批通过后才能调用")
    public RefundExecution createRefund(@ToolParam(description = "订单号") String orderId,
                                        @ToolParam(description = "退款金额") BigDecimal amount,
                                        @ToolParam(description = "退款原因") String reason,
                                        ToolContext context) {
        if (!Boolean.TRUE.equals(context.getContext().get("refundAuthorized"))) {
            throw new IllegalStateException("Missing server-side refund authorization");
        }
        Order order = getOrder(orderId);
        if (amount == null || amount.signum() <= 0 || amount.compareTo(order.paidAmount()) != 0
                || reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Refund must match the paid amount and have a reason");
        }
        if ("O-4001".equals(orderId)) {
            return new RefundExecution("ALREADY_REFUNDED", "RF-0999",
                    new BigDecimal("129.00"), "订单已经退款");
        }
        if (!"PAID".equals(order.status())) {
            throw new IllegalArgumentException("Order is not paid");
        }
        var record = refunds.compute(orderId, (ignored, existing) -> {
            if (existing != null && existing.amount().compareTo(amount) != 0) {
                throw new IllegalStateException("Idempotency key already belongs to a different amount");
            }
            return existing == null ? new RefundRecord("RF-" + refundSequence.incrementAndGet(), amount) : existing;
        });
        return new RefundExecution("SUCCESS", record.refundId(), record.amount(), "退款单已创建");
    }

    private Order order(String orderId, String status, String amount, int deliveredDaysAgo,
                        boolean chargebackRisk) {
        return new Order(orderId, status, new BigDecimal(amount),
                clock.instant().minus(deliveredDaysAgo, ChronoUnit.DAYS), chargebackRisk);
    }

    private record RefundRecord(String refundId, BigDecimal amount) {
    }
}
