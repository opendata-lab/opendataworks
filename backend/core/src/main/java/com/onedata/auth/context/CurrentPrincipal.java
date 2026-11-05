package com.onedata.auth.context;

import lombok.Getter;

/** Immutable authenticated subject. Delegated identity only narrows read access. */
@Getter
public final class CurrentPrincipal {
    public static final String ROLE_ADMIN = "admin",
            ROLE_USER = "user",
            AUTH_SOURCE_LOCAL = "local",
            AUTH_SOURCE_ANONYMOUS = "anonymous";

    public enum AccountType {
        USER,
        SERVICE
    }

    public enum AuthenticationMethod {
        SESSION,
        SERVICE_TOKEN
    }

    private final Long subjectId;
    private final AccountType accountType;
    private final AuthenticationMethod authenticationMethod;
    private final String username, role, authSource, delegatedUserKey;
    private final Integer sessionVersion;

    public CurrentPrincipal(String id, String username, String role, String source) {
        this(id, username, role, source, 0);
    }

    public CurrentPrincipal(
            String id, String username, String role, String source, Integer version) {
        this(
                Long.valueOf(id),
                AccountType.USER,
                AuthenticationMethod.SESSION,
                username,
                role,
                source,
                version,
                null);
    }

    public CurrentPrincipal(
            Long id,
            AccountType type,
            AuthenticationMethod method,
            String username,
            String role,
            String source,
            Integer version,
            String delegate) {
        this.subjectId = java.util.Objects.requireNonNull(id);
        this.accountType = java.util.Objects.requireNonNull(type);
        this.authenticationMethod = java.util.Objects.requireNonNull(method);
        this.username = username;
        this.role = type == AccountType.USER ? role : null;
        this.authSource = source;
        this.sessionVersion = version;
        this.delegatedUserKey = delegate;
    }

    public static CurrentPrincipal service(Long id, String delegate) {
        return new CurrentPrincipal(
                id,
                AccountType.SERVICE,
                AuthenticationMethod.SERVICE_TOKEN,
                String.valueOf(id),
                null,
                "service",
                null,
                delegate);
    }

    public String getUserId() {
        return String.valueOf(subjectId);
    }

    public boolean isAdmin() {
        return accountType == AccountType.USER && ROLE_ADMIN.equals(role);
    }
}
