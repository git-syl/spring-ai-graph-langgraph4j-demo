package com.example.refunddemo.refund.schema;

import java.math.BigDecimal;

public record PolicyDecision(boolean eligible,
                             boolean needsApproval,
                             String reason,
                             BigDecimal refundableAmount) {
}
