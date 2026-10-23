package com.onedata.portal.service;

import com.onedata.portal.dto.DorisCredential;
import com.onedata.portal.entity.DorisDbUser;
import com.onedata.portal.entity.UserDatabasePermission;
import com.onedata.portal.mapper.DorisDbUserMapper;
import com.onedata.portal.mapper.UserDatabasePermissionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * UserMappingService 单元测试
 */
@ExtendWith(MockitoExtension.class)
class UserMappingServiceTest {

    @Mock
    private UserDatabasePermissionMapper userDatabasePermissionMapper;

    @Mock
    private DorisDbUserMapper dorisDbUserMapper;

    @InjectMocks
    private UserMappingService userMappingService;

    private UserDatabasePermission readonlyPermission;
    private UserDatabasePermission readwritePermission;
    private DorisDbUser dorisDbUser;

    @BeforeEach
    void setUp() {
        // 准备测试数据
        readonlyPermission = new UserDatabasePermission();
        readonlyPermission.setUserId("user123");
        readonlyPermission.setClusterId(1L);
        readonlyPermission.setDatabaseName("test_db");
        readonlyPermission.setPermissionLevel("readonly");

        readwritePermission = new UserDatabasePermission();
        readwritePermission.setUserId("user456");
        readwritePermission.setClusterId(1L);
        readwritePermission.setDatabaseName("test_db");
        readwritePermission.setPermissionLevel("readwrite");

        dorisDbUser = new DorisDbUser();
        dorisDbUser.setClusterId(1L);
        dorisDbUser.setDatabaseName("test_db");
        dorisDbUser.setReadonlyUsername("test_db_ro");
        dorisDbUser.setReadonlyPassword("ro_password");
        dorisDbUser.setReadwriteUsername("test_db_rw");
        dorisDbUser.setReadwritePassword("rw_password");
    }

    @Test
    void testGetDorisCredential_ReadonlyUser() {
        // Given: 用户有只读权限
        when(userDatabasePermissionMapper.selectOne(any())).thenReturn(readonlyPermission);
        when(dorisDbUserMapper.selectOne(any())).thenReturn(dorisDbUser);

        // When: 获取Doris凭据
        DorisCredential credential = userMappingService.getDorisCredential("user123", 1L, "test_db");

        // Then: 应该返回只读用户凭据
        assertNotNull(credential);
        assertEquals("test_db_ro", credential.getUsername());
        assertEquals("ro_password", credential.getPassword());
    }

    @Test
    void testGetDorisCredential_ReadwriteUser() {
        // Given: 用户有读写权限
        when(userDatabasePermissionMapper.selectOne(any())).thenReturn(readwritePermission);
        when(dorisDbUserMapper.selectOne(any())).thenReturn(dorisDbUser);

        // When: 获取Doris凭据
        DorisCredential credential = userMappingService.getDorisCredential("user456", 1L, "test_db");

        // Then: 应该返回读写用户凭据
        assertNotNull(credential);
        assertEquals("test_db_rw", credential.getUsername());
        assertEquals("rw_password", credential.getPassword());
    }

    @Test
    void testGetDorisCredential_NoPermission() {
        // Given: 该库登记了映射账号，但用户没有授权
        when(dorisDbUserMapper.selectOne(any())).thenReturn(dorisDbUser);
        when(userDatabasePermissionMapper.selectOne(any())).thenReturn(null);

        // When & Then: 应该抛出异常
        RuntimeException exception = assertThrows(RuntimeException.class, () -> {
            userMappingService.getDorisCredential("user789", 1L, "test_db");
        });

        assertTrue(exception.getMessage().contains("用户没有访问数据库"));
    }

    @Test
    void testGetDorisCredential_NoMappedAccountMeansMappingNotInUse() {
        // Given: 该库没有登记映射账号，无论用户是否有授权记录
        when(dorisDbUserMapper.selectOne(any())).thenReturn(null);

        // When & Then: 返回 null，由调用方使用数据源账号
        assertNull(userMappingService.getDorisCredential("user123", 1L, "test_db"));
        verify(userDatabasePermissionMapper, never()).selectOne(any());
    }

    @Test
    void testHasPermission_WithPermission() {
        // Given: 用户有权限
        when(userDatabasePermissionMapper.selectCount(any())).thenReturn(1L);

        // When: 检查权限
        boolean hasPermission = userMappingService.hasPermission("user123", 1L, "test_db");

        // Then: 应该返回true
        assertTrue(hasPermission);
    }

    @Test
    void testHasPermission_NoPermission() {
        // Given: 用户没有权限
        when(userDatabasePermissionMapper.selectCount(any())).thenReturn(0L);

        // When: 检查权限
        boolean hasPermission = userMappingService.hasPermission("user789", 1L, "test_db");

        // Then: 应该返回false
        assertFalse(hasPermission);
    }
}
