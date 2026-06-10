package com.onedata.auth.context;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * UserContextHolder测试
 * 测试ThreadLocal的正确性和线程安全性
 */
class UserContextHolderTest {

    @AfterEach
    void tearDown() {
        UserContextHolder.clear();
    }

    @Test
    void testSetAndGetContext() {
        UserContext context = new UserContext("user123", "testuser", "admin", "local");

        UserContextHolder.setContext(context);

        UserContext retrieved = UserContextHolder.getContext();

        assertNotNull(retrieved);
        assertEquals("user123", retrieved.getUserId());
        assertEquals("testuser", retrieved.getUsername());
        assertEquals("admin", retrieved.getRole());
        assertEquals("local", retrieved.getAuthSource());
        assertTrue(UserContextHolder.isAuthenticated());
    }

    @Test
    void testGetCurrentUserId() {
        UserContextHolder.setContext(new UserContext("user456", "anotheruser", "user", "local"));

        assertEquals("user456", UserContextHolder.getCurrentUserId());
    }

    @Test
    void testGetCurrentUsername() {
        UserContextHolder.setContext(new UserContext("user789", "thirduser", "user", "local"));

        assertEquals("thirduser", UserContextHolder.getCurrentUsername());
    }

    @Test
    void testClearContext() {
        UserContextHolder.setContext(new UserContext("user999", "clearuser", "user", "local"));

        assertNotNull(UserContextHolder.getContext());

        UserContextHolder.clear();

        assertNull(UserContextHolder.getContext());
        assertFalse(UserContextHolder.isAuthenticated());
    }

    @Test
    void testSetNullContext() {
        UserContextHolder.setContext(null);

        assertNull(UserContextHolder.getContext());
    }

    @Test
    void testGetContextWhenNotSet() {
        assertNull(UserContextHolder.getContext());
        assertFalse(UserContextHolder.isAuthenticated());
    }

    @Test
    void testThreadIsolation() throws InterruptedException {
        UserContext mainContext = new UserContext("main-user", "mainuser", "admin", "local");
        UserContextHolder.setContext(mainContext);

        CountDownLatch latch = new CountDownLatch(1);
        List<UserContext> otherThreadContext = new ArrayList<>();

        Thread otherThread = new Thread(() -> {
            otherThreadContext.add(UserContextHolder.getContext());

            UserContextHolder.setContext(new UserContext("thread-user", "threaduser", "user", "local"));

            latch.countDown();
        });

        otherThread.start();
        latch.await(5, TimeUnit.SECONDS);
        otherThread.join();

        assertNull(otherThreadContext.get(0));

        UserContext mainRetrieved = UserContextHolder.getContext();
        assertNotNull(mainRetrieved);
        assertEquals("main-user", mainRetrieved.getUserId());
    }

    @Test
    void testConcurrentAccess() throws InterruptedException {
        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        List<String> results = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    UserContextHolder.setContext(new UserContext(
                            "user-" + threadId,
                            "username-" + threadId,
                            "user",
                            "local"));

                    Thread.sleep(10);

                    UserContext retrieved = UserContextHolder.getContext();
                    synchronized (results) {
                        results.add(retrieved.getUserId());
                    }

                    UserContextHolder.clear();
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
            assertTrue(results.contains("user-" + i));
        }
    }
}
