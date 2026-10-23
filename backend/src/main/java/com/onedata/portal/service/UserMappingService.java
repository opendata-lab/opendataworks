package com.onedata.portal.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.onedata.portal.dto.DorisCredential;
import com.onedata.portal.entity.DorisDbUser;
import com.onedata.portal.entity.UserDatabasePermission;
import com.onedata.portal.mapper.DorisDbUserMapper;
import com.onedata.portal.mapper.UserDatabasePermissionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 用户映射服务
 * 负责将平台用户映射到对应的Doris用户凭据
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserMappingService {

    private final UserDatabasePermissionMapper userDatabasePermissionMapper;
    private final DorisDbUserMapper dorisDbUserMapper;

    /**
     * 获取用户对指定数据库的映射凭据。
     *
     * <p>该库没有登记映射账号时返回 {@code null}，表示未启用映射，由调用方使用数据源账号；
     * 登记了映射账号但用户没有授权时抛出异常，调用方不得再退回数据源账号，否则授权形同虚设。
     *
     * @param userId       用户ID
     * @param clusterId    数据源ID
     * @param databaseName 数据库名（PostgreSQL 为 schema）
     * @return 映射凭据；未启用映射时为 {@code null}
     */
    public DorisCredential getDorisCredential(String userId, Long clusterId, String databaseName) {
        DorisDbUser dbUser = dorisDbUserMapper.selectOne(
                new LambdaQueryWrapper<DorisDbUser>()
                        .eq(DorisDbUser::getClusterId, clusterId)
                        .eq(DorisDbUser::getDatabaseName, databaseName)
        );
        if (dbUser == null) {
            log.debug("No mapped account registered for database {} on cluster {}", databaseName, clusterId);
            return null;
        }

        UserDatabasePermission permission = userDatabasePermissionMapper.selectOne(
                new LambdaQueryWrapper<UserDatabasePermission>()
                        .eq(UserDatabasePermission::getUserId, userId)
                        .eq(UserDatabasePermission::getClusterId, clusterId)
                        .eq(UserDatabasePermission::getDatabaseName, databaseName)
        );
        if (permission == null) {
            log.warn("User {} has no permission for database {} on cluster {}", userId, databaseName, clusterId);
            throw new RuntimeException("用户没有访问数据库 " + databaseName + " 的权限");
        }

        if ("readonly".equals(permission.getPermissionLevel())) {
            log.debug("User {} mapped to readonly user for database {}", userId, databaseName);
            return new DorisCredential(dbUser.getReadonlyUsername(), dbUser.getReadonlyPassword());
        } else if ("readwrite".equals(permission.getPermissionLevel())) {
            log.debug("User {} mapped to readwrite user for database {}", userId, databaseName);
            return new DorisCredential(dbUser.getReadwriteUsername(), dbUser.getReadwritePassword());
        } else {
            log.error("Unknown permission level: {}", permission.getPermissionLevel());
            throw new RuntimeException("未知的权限级别: " + permission.getPermissionLevel());
        }
    }

    /**
     * 检查用户是否有数据库访问权限
     *
     * @param userId       用户ID
     * @param clusterId    集群ID
     * @param databaseName 数据库名
     * @return 是否有权限
     */
    public boolean hasPermission(String userId, Long clusterId, String databaseName) {
        return userDatabasePermissionMapper.selectCount(
                new LambdaQueryWrapper<UserDatabasePermission>()
                        .eq(UserDatabasePermission::getUserId, userId)
                        .eq(UserDatabasePermission::getClusterId, clusterId)
                        .eq(UserDatabasePermission::getDatabaseName, databaseName)
        ) > 0;
    }
}
