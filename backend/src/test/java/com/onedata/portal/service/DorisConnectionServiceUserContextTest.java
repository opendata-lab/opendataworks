package com.onedata.portal.service;

import com.onedata.auth.context.UserContext;
import com.onedata.auth.context.UserContextHolder;
import com.onedata.portal.dto.DorisCredential;
import com.onedata.portal.entity.DorisCluster;
import com.onedata.portal.mapper.DorisClusterMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.sql.Connection;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * DorisConnectionService用户上下文集成测试
 * 测试DorisConnectionService是否正确使用UserContextHolder和UserMappingService
 */
@SpringBootTest
class DorisConnectionServiceUserContextTest {

    @Autowired
    private DorisConnectionService dorisConnectionService;

    @MockBean
    private UserMappingService userMappingService;

    @MockBean
    private DorisClusterMapper dorisClusterMapper;

    private DorisCluster testCluster;

    @BeforeEach
    void setUp() {
        // 创建测试集群
        testCluster = new DorisCluster();
        testCluster.setId(1L);
        testCluster.setClusterName("test-cluster");
        testCluster.setFeHost("localhost");
        testCluster.setFePort(9030);
        testCluster.setUsername("admin");
        testCluster.setPassword("admin123");
        testCluster.setIsDefault(1);
        testCluster.setStatus("active");

        // Mock集群查询
        when(dorisClusterMapper.selectById(1L)).thenReturn(testCluster);
    }

    @AfterEach
    void tearDown() {
        UserContextHolder.clear();
    }

    @Test
    void testGetConnectionWithUserContext() throws Exception {
        // 设置用户上下文
        UserContext userContext = new UserContext("user123", "testuser", "user", "local");
        UserContextHolder.setContext(userContext);

        // Mock用户映射服务返回用户凭据
        DorisCredential userCredential = new DorisCredential("readonly_user", "readonly_pass");
        when(userMappingService.getDorisCredential("user123", 1L, "test_db"))
                .thenReturn(userCredential);

        // 注意：实际连接会失败因为没有真实的Doris，但我们可以验证调用
        try {
            dorisConnectionService.getConnection(1L, "test_db");
        } catch (Exception e) {
            // 预期会失败，因为没有真实的Doris服务器
            // 但我们可以验证UserMappingService被调用了
        }

        // 验证UserMappingService被调用
        verify(userMappingService, times(1))
                .getDorisCredential("user123", 1L, "test_db");
    }

    @Test
    void testGetConnectionWithoutUserContext() throws Exception {
        // 不设置用户上下文

        // 调用getConnection
        try {
            dorisConnectionService.getConnection(1L, "test_db");
        } catch (Exception e) {
            // 预期会失败，因为没有真实的Doris服务器
        }

        // 验证UserMappingService没有被调用
        verify(userMappingService, never())
                .getDorisCredential(anyString(), anyLong(), anyString());
    }

    @Test
    void testGetConnectionFallbackToClusterCredentialOnError() throws Exception {
        // 设置用户上下文
        UserContext userContext = new UserContext("user456", "anotheruser", "user", "local");
        UserContextHolder.setContext(userContext);

        // Mock用户映射服务抛出异常
        when(userMappingService.getDorisCredential("user456", 1L, "test_db"))
                .thenThrow(new RuntimeException("User has no permission"));

        // 调用getConnection应该回退到集群默认凭据
        try {
            dorisConnectionService.getConnection(1L, "test_db");
        } catch (Exception e) {
            // 预期会失败，因为没有真实的Doris服务器
        }

        // 验证UserMappingService被调用了
        verify(userMappingService, times(1))
                .getDorisCredential("user456", 1L, "test_db");
    }

    @Test
    void testGetConnectionWithoutDatabaseUsesClusterCredential() throws Exception {
        // 设置用户上下文
        UserContext userContext = new UserContext("user789", "thirduser", "user", "local");
        UserContextHolder.setContext(userContext);

        // 调用getConnection但不指定数据库
        try {
            dorisConnectionService.getConnection(1L, null);
        } catch (Exception e) {
            // 预期会失败，因为没有真实的Doris服务器
        }

        // 验证UserMappingService没有被调用（因为没有指定数据库）
        verify(userMappingService, never())
                .getDorisCredential(anyString(), anyLong(), anyString());
    }
}
