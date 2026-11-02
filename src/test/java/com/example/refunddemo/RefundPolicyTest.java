package com.example.refunddemo;

import com.example.refunddemo.refund.RefundPolicy;
import com.example.refunddemo.refund.schema.Order;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RefundPolicyTest {
    private static final Instant NOW = Instant.parse("2026-11-01T00:00:00Z");
    private final RefundPolicy policy = new RefundPolicy(Duration.ofDays(30), new BigDecimal("300.00"));

    @Test
    void thresholdAndRiskRequireApproval() {
        assertThat(policy.evaluate(order("299.99", NOW, false), NOW).needsApproval()).isFalse();
        assertThat(policy.evaluate(order("300.00", NOW, false), NOW).needsApproval()).isTrue();
        assertThat(policy.evaluate(order("1.00", NOW, true), NOW).needsApproval()).isTrue();
    }

    @Test
    void exactWindowIsEligibleButAnyLaterIsRejected() {
        var order = order("99", NOW.minus(Duration.ofDays(30)), false);
        assertThat(policy.evaluate(order, NOW).eligible()).isTrue();
        assertThat(policy.evaluate(order, NOW.plusNanos(1)).eligible()).isFalse();
    }

    @Test
    void invalidBusinessFactsAreRejected() {
        assertThat(policy.evaluate(order("99", NOW.plusNanos(1), false), NOW).eligible()).isFalse();
        assertThat(policy.evaluate(order("0", NOW, false), NOW).eligible()).isFalse();
        assertThat(policy.evaluate(order("-1", NOW, false), NOW).eligible()).isFalse();
        assertThat(policy.evaluate(new Order("O-1", "REFUNDED", new BigDecimal("99"), NOW, false), NOW).eligible()).isFalse();
        assertThatThrownBy(() -> new RefundPolicy(Duration.ZERO, BigDecimal.ONE)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RefundPolicy(Duration.ofDays(30), BigDecimal.ZERO)).isInstanceOf(IllegalArgumentException.class);
    }

    private static Order order(String amount, Instant delivered, boolean risk) {
        return new Order("O-1", "PAID", new BigDecimal(amount), delivered, risk);
    }
}
