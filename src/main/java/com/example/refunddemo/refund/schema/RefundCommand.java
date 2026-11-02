package com.example.refunddemo.refund.schema;

/** Validated business input, collected by the assistant before invoking the graph. */
public record RefundCommand(String orderId, String reason) {
    public RefundCommand {
        if (orderId == null || !orderId.matches("O-[0-9]{1,20}")) {
            throw new IllegalArgumentException("请提供有效订单号，例如 O-1001");
        }
        if (reason == null || reason.isBlank() || reason.length() > 1000) {
            throw new IllegalArgumentException("退款原因必须为 1-1000 个字符");
        }
        reason = reason.strip();
    }
}
