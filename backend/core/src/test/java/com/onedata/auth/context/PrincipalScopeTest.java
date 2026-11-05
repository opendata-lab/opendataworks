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
        CurrentPrincipal service = CurrentPrincipal.service(7L, "local:alice");
        CurrentPrincipal other = CurrentPrincipal.service(7L, "local:bob");
        Object first = new Object();
        PrincipalContextHolder.runWith(
                service,
                () -> {
                    assertSame(first, PrincipalContextHolder.cached("columns", () -> first));
                    PrincipalContextHolder.runWith(
                            CurrentPrincipal.service(7L, "local:alice"),
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
                                                    CurrentPrincipal.service(7L, null),
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
                        "local:admin");
        assertFalse(p.isAdmin());
        assertNull(p.getRole());
    }
}
