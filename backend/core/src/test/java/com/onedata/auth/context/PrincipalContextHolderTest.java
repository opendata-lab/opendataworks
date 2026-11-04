package com.onedata.auth.context;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** PrincipalContextHolder测试 测试ThreadLocal的正确性和线程安全性 */
class PrincipalContextHolderTest {

    @AfterEach
    void tearDown() {
        PrincipalContextHolder.clear();
    }

    @Test
    void testSetAndGetContext() {
        CurrentPrincipal context = new CurrentPrincipal("123", "testuser", "admin", "local");

        PrincipalContextHolder.setContext(context);

        CurrentPrincipal retrieved = PrincipalContextHolder.getContext();

        assertNotNull(retrieved);
        assertEquals("123", retrieved.getUserId());
        assertEquals("testuser", retrieved.getUsername());
        assertEquals("admin", retrieved.getRole());
        assertEquals("local", retrieved.getAuthSource());
        assertTrue(PrincipalContextHolder.isAuthenticated());
    }

    @Test
    void testGetCurrentUserId() {
        PrincipalContextHolder.setContext(
                new CurrentPrincipal("123", "anotheruser", "user", "local"));

        assertEquals("123", PrincipalContextHolder.getCurrentUserId());
    }

    @Test
    void testGetCurrentUsername() {
        PrincipalContextHolder.setContext(
                new CurrentPrincipal("123", "thirduser", "user", "local"));

        assertEquals("thirduser", PrincipalContextHolder.getCurrentUsername());
    }

    @Test
    void testClearContext() {
        PrincipalContextHolder.setContext(
                new CurrentPrincipal("123", "clearuser", "user", "local"));

        assertNotNull(PrincipalContextHolder.getContext());

        PrincipalContextHolder.clear();

        assertNull(PrincipalContextHolder.getContext());
        assertFalse(PrincipalContextHolder.isAuthenticated());
    }

    @Test
    void testSetNullContext() {
        PrincipalContextHolder.setContext(null);

        assertNull(PrincipalContextHolder.getContext());
    }

    @Test
    void testGetContextWhenNotSet() {
        assertNull(PrincipalContextHolder.getContext());
        assertFalse(PrincipalContextHolder.isAuthenticated());
    }

    @Test
    void testThreadIsolation() throws InterruptedException {
        CurrentPrincipal mainContext = new CurrentPrincipal("123", "mainuser", "admin", "local");
        PrincipalContextHolder.setContext(mainContext);

        CountDownLatch latch = new CountDownLatch(1);
        List<CurrentPrincipal> otherThreadContext = new ArrayList<>();

        Thread otherThread =
                new Thread(
                        () -> {
                            otherThreadContext.add(PrincipalContextHolder.getContext());

                            PrincipalContextHolder.setContext(
                                    new CurrentPrincipal("123", "threaduser", "user", "local"));

                            latch.countDown();
                        });

        otherThread.start();
        latch.await(5, TimeUnit.SECONDS);
        otherThread.join();

        assertNull(otherThreadContext.get(0));

        CurrentPrincipal mainRetrieved = PrincipalContextHolder.getContext();
        assertNotNull(mainRetrieved);
        assertEquals("123", mainRetrieved.getUserId());
    }

    @Test
    void testConcurrentAccess() throws InterruptedException {
        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        List<String> results = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            executor.submit(
                    () -> {
                        try {
                            PrincipalContextHolder.setContext(
                                    new CurrentPrincipal(
                                            String.valueOf(threadId),
                                            "username-" + threadId,
                                            "user",
                                            "local"));

                            Thread.sleep(10);

                            CurrentPrincipal retrieved = PrincipalContextHolder.getContext();
                            synchronized (results) {
                                results.add(retrieved.getUserId());
                            }

                            PrincipalContextHolder.clear();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } finally {
                            latch.countDown();
                        }
                    });
        }

        latch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertEquals(threadCount, results.size());
        for (int i = 0; i < threadCount; i++) {
            assertTrue(results.contains(String.valueOf(i)));
        }
    }
}
