package com.example.refunddemo.assistant;

/** SSE envelope: token, workflow_step, workflow_result, approval_required, result or error. */
public record AssistantEvent(String type, Object data) { }
