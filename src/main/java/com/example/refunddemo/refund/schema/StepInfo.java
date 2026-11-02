package com.example.refunddemo.refund.schema;

import com.fasterxml.jackson.annotation.JsonValue;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/** Transport-neutral progress event, analogous to the Python WSStepInfo contract. */
public record StepInfo(String threadId, String step, Status status, Map<String, Object> data,
                       boolean finish, Instant timestamp) {
    public StepInfo {
        Objects.requireNonNull(threadId);
        Objects.requireNonNull(step);
        Objects.requireNonNull(status);
        data = Map.copyOf(data);
        Objects.requireNonNull(timestamp);
    }

    public enum Status {
        RUNNING, SUCCESS, ERROR, WAITING;

        @JsonValue
        public String value() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }
}
