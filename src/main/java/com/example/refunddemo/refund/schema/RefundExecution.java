package com.example.refunddemo.refund.schema;

import java.math.BigDecimal;

public record RefundExecution(String status,
                              String refundId,
                              BigDecimal amount,
                              String message) {
}
