package com.prizeflux.ai.harness.safety;

public enum Role {
    USER,
    OPERATOR;

    public boolean allows(Role required) {
        return this == required || this == OPERATOR && required == USER;
    }
}
