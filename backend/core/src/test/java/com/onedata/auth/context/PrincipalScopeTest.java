package com.onedata.auth.context;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.*;

import java.util.concurrent.*;

class PrincipalScopeTest {
    @AfterEach
    void clear() {
        PrincipalContextHolder.clear();
    }

    @Test
    void nestedExceptionsRestorePrincipalAndSubjectCache() {
        CurrentPrincipal service = CurrentPrincipal.service(7L, "agent_x", "local:alice");
        // Same token, different reported user: same access, so the cache is shared.
        CurrentPrincipal sameAccess = CurrentPrincipal.service(7L, "agent_x", "local:bob");
        CurrentPrincipal other = CurrentPrincipal.service(8L);
        Object first = new Object();
        PrincipalContextHolder.runWith(
                service,
                () -> {
                    assertSame(first, PrincipalContextHolder.cached("columns", () -> first));
                    PrincipalContextHolder.runWith(
                            sameAccess,
                            () -> {
                                assertSame(
                                        first,
                                        PrincipalContextHolder.cached("columns", Object::new));
                                return null;
                            });
                    assertThrows(
                            IllegalStateException.class,
                            () ->
                                    PrincipalContextHolder.runWith(
                                            other,
                                            () -> {
                                                assertNotSame(
                                                        first,
                                                        PrincipalContextHolder.cached(
                                                                "columns", Object::new));
                                                throw new IllegalStateException("fail");
                                            }));
                    assertSame(service, PrincipalContextHolder.require());
                    assertSame(first, PrincipalContextHolder.cached("columns", Object::new));
                    return null;
                });
        assertNull(PrincipalContextHolder.getContext());
    }

    @Test
    void reusedWorkerCannotLeakPreviousSubject() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            assertEquals(
                    "7",
                    executor.submit(
                                    () ->
                                            PrincipalContextHolder.runWith(
                                                    CurrentPrincipal.service(7L),
                                                    PrincipalContextHolder::getCurrentUserId))
                            .get());
            assertNull(executor.submit(PrincipalContextHolder::getContext).get());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void serviceCannotBecomeAdministrator() {
        CurrentPrincipal p =
                new CurrentPrincipal(
                        7L,
                        CurrentPrincipal.AccountType.SERVICE,
                        CurrentPrincipal.AuthenticationMethod.SERVICE_TOKEN,
                        "svc",
                        "admin",
                        "service",
                        0,
                        null,
                        "local:admin");
        assertFalse(p.isAdmin());
        assertNull(p.getRole());
    }
}
