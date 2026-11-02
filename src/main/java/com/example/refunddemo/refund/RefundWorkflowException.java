package com.example.refunddemo.refund;

/** Application errors independent of HTTP, SSE and model-provider exception details. */
public final class RefundWorkflowException extends RuntimeException {
    public enum Kind { NOT_FOUND, CONFLICT, FAILED }
    private final Kind kind;

    public RefundWorkflowException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public RefundWorkflowException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind kind() { return kind; }
}
