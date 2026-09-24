package com.prizeflux.campaign.application;

/** 账号的最小持久化视图；绝不在 HTTP 响应或日志中序列化 passwordHash。 */
public record AppUser(long id, String username, String passwordHash, Role role, State state) {
    public enum Role { USER, OPERATOR }
    public enum State { ACTIVE, DISABLED }

    public AppUser {
        if (id <= 0 || username == null || !username.matches("[a-z0-9._-]{3,64}")
                || passwordHash == null || passwordHash.length() < 59
                || !passwordHash.startsWith("$2") || role == null || state == null) {
            throw new IllegalArgumentException("Invalid persisted account");
        }
    }
}
