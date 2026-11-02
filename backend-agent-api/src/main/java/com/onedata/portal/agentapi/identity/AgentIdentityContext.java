package com.onedata.portal.agentapi.identity;

import lombok.Value;

public final class AgentIdentityContext {
    public static final String USER_HEADER = "X-Agent-User-Id";
    public static final String AGENT_HEADER = "X-Agent-Id";
    private static final ThreadLocal<Identity> CURRENT = new ThreadLocal<>();

    private AgentIdentityContext() {
    }

    public static void set(String userId, String agentId) {
        if (agentId == null || agentId.trim().isEmpty()) {
            throw new IllegalArgumentException("缺少已认证的服务账号身份");
        }
        CURRENT.set(new Identity(userId == null || userId.trim().isEmpty() ? null : userId.trim(), agentId.trim()));
    }

    public static Identity require() {
        Identity identity = CURRENT.get();
        if (identity == null) {
            throw new IllegalArgumentException("缺少服务账号身份上下文");
        }
        return identity;
    }

    public static void clear() {
        CURRENT.remove();
    }

    @Value
    public static class Identity {
        String userId;
        String agentId;
    }
}
