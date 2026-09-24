package com.prizeflux.ai.harness.application;

public enum RunStatus {
    COMPLETED,
    MODEL_TURN_LIMIT,
    TOOL_CALL_LIMIT,
    DEADLINE_EXCEEDED,
    INVALID_MODEL_ACTION,
    MODEL_FAILURE,
    TOOL_FAILURE
}
