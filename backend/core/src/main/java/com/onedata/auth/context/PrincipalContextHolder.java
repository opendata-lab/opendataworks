package com.onedata.auth.context;

import java.util.*;
import java.util.function.Supplier;

/** Scope-bound principal and request cache, restored across nested execution. */
public final class PrincipalContextHolder {
    private record Scope(CurrentPrincipal principal, Map<Object, Object> cache) {}

    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();

    private PrincipalContextHolder() {}

    public static CurrentPrincipal getContext() {
        Scope s = CURRENT.get();
        return s == null ? null : s.principal();
    }

    public static CurrentPrincipal require() {
        return Objects.requireNonNull(getContext(), "缺少认证主体");
    }

    public static void setContext(CurrentPrincipal p) {
        if (p != null) CURRENT.set(new Scope(p, new HashMap<>()));
    }

    public static void clear() {
        CURRENT.remove();
    }

    public static String getCurrentUserId() {
        return getContext() == null ? null : getContext().getUserId();
    }

    public static String getCurrentUsername() {
        return getContext() == null ? null : getContext().getUsername();
    }

    public static boolean isAuthenticated() {
        return getContext() != null;
    }

    private static boolean same(CurrentPrincipal a, CurrentPrincipal b) {
        return a.getSubjectId().equals(b.getSubjectId())
                && a.getAccountType() == b.getAccountType()
                && Objects.equals(a.getDelegatedUserKey(), b.getDelegatedUserKey());
    }

    public static <T> T runWith(CurrentPrincipal p, Supplier<T> action) {
        Objects.requireNonNull(p);
        Scope previous = CURRENT.get();
        CURRENT.set(
                new Scope(
                        p,
                        previous != null && same(previous.principal(), p)
                                ? previous.cache()
                                : new HashMap<>()));
        try {
            return action.get();
        } finally {
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        }
    }

    @SuppressWarnings("unchecked")
    public static <T> T cached(Object key, Supplier<T> load) {
        Scope s = CURRENT.get();
        if (s == null) throw new IllegalStateException("缺少认证作用域");
        return (T) s.cache().computeIfAbsent(key, k -> load.get());
    }
}
