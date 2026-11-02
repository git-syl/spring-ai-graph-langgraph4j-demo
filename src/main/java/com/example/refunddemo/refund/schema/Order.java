package com.example.refunddemo.refund.schema;

import java.math.BigDecimal;
import java.time.Instant;

public record Order(String orderId,
                    String status,
                    BigDecimal paidAmount,
                    Instant deliveredAt,
                    boolean chargebackRisk) {
}
