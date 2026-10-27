package com.onedata.portal.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.onedata.auth.context.UserContext;
import com.onedata.auth.context.UserContextHolder;
import com.onedata.auth.user.SysUser;
import com.onedata.auth.user.SysUserMapper;
import com.onedata.portal.entity.DatasourceSubjectAccount;
import com.onedata.portal.entity.DorisCluster;
import com.onedata.portal.mapper.DatasourceSubjectAccountMapper;
import com.onedata.portal.mapper.DorisClusterMapper;
import com.onedata.portal.service.datasource.core.DatasourceConnectionFactory;
import com.onedata.portal.service.datasource.model.ConnectionConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

@Service
@RequiredArgsConstructor
public class DatasourceSubjectAccountService {
    private final DatasourceSubjectAccountMapper accountMapper;
    private final DorisClusterMapper clusterMapper;
    private final SysUserMapper userMapper;
    private final DatasourceConnectionFactory connectionFactory;

    public DatasourceSubjectAccount require(Long sourceId, String type, String subjectId) {
        DatasourceSubjectAccount account = accountMapper.selectOne(new LambdaQueryWrapper<DatasourceSubjectAccount>()
                .eq(DatasourceSubjectAccount::getSourceId, sourceId)
                .eq(DatasourceSubjectAccount::getSubjectType, type)
                .eq(DatasourceSubjectAccount::getSubjectId, subjectId));
        if (account == null) {
            throw new IllegalArgumentException("未配置" + ("USER".equals(type) ? "用户" : "智能体") + "的数据源账号映射");
        }
        return account;
    }

    public String currentUserId() {
        UserContext context = UserContextHolder.getContext();
        if (context == null || "anonymous".equals(context.getAuthSource())) {
            throw new IllegalArgumentException("请登录后访问数据源");
        }
        SysUser user = userMapper.selectById(context.getUserId());
        requireEnabled(user);
        return String.valueOf(user.getId());
    }

    public SysUser resolveUser(String identity) {
        int separator = identity == null ? -1 : identity.indexOf(':');
        if (separator <= 0 || separator == identity.length() - 1) {
            throw new IllegalArgumentException("缺少有效的用户标识");
        }
        String source = identity.substring(0, separator);
        String subject = identity.substring(separator + 1);
        LambdaQueryWrapper<SysUser> query = new LambdaQueryWrapper<>();
        // 来源前缀决定同一查询使用的标识字段；不按用户名猜测 OAuth 身份。
        boolean local = "local".equals(source);
        query.eq(local ? SysUser::getUsername : SysUser::getExternalId, subject);
        if (local) {
            query.eq(SysUser::getAuthSource, "local");
        } else {
            query.ne(SysUser::getAuthSource, "local");
        }
        SysUser user = userMapper.selectOne(query);
        requireEnabled(user);
        return user;
    }

    private void requireEnabled(SysUser user) {
        if (user == null || !Boolean.TRUE.equals(user.getEnabled()) || Integer.valueOf(1).equals(user.getDeleted())) {
            throw new IllegalArgumentException("用户不存在或已停用");
        }
    }

    public Connection open(Long sourceId, String type, String subjectId, String namespace, boolean readOnly)
            throws SQLException {
        DorisCluster cluster = clusterMapper.selectById(sourceId);
        if (cluster == null) {
            throw new IllegalArgumentException("数据源不存在");
        }
        DatasourceSubjectAccount account = require(sourceId, type, subjectId);
        return connectionFactory.open(cluster.getSourceType(), ConnectionConfig.from(cluster), namespace,
                account.getUsername(), account.getPassword(), readOnly);
    }

    public List<DatasourceSubjectAccount> list(Long sourceId) {
        return accountMapper.selectList(new LambdaQueryWrapper<DatasourceSubjectAccount>()
                .eq(DatasourceSubjectAccount::getSourceId, sourceId).orderByAsc(DatasourceSubjectAccount::getId));
    }

    public DatasourceSubjectAccount save(Long sourceId, DatasourceSubjectAccount account) {
        if (!"USER".equals(account.getSubjectType()) && !"AGENT".equals(account.getSubjectType())) {
            throw new IllegalArgumentException("主体类型必须为 USER 或 AGENT");
        }
        if (!StringUtils.hasText(account.getSubjectId()) || !StringUtils.hasText(account.getUsername())) {
            throw new IllegalArgumentException("主体和数据库用户名不能为空");
        }
        account.setSourceId(sourceId);
        if (account.getId() != null) {
            DatasourceSubjectAccount old = accountMapper.selectById(account.getId());
            if (old == null || !sourceId.equals(old.getSourceId())) {
                throw new IllegalArgumentException("账号映射不存在");
            }
            if (account.getPassword() == null) {
                account.setPassword(old.getPassword());
            }
        }
        if ("USER".equals(account.getSubjectType())) {
            requireEnabled(userMapper.selectById(account.getSubjectId()));
        }
        DorisCluster cluster = clusterMapper.selectById(sourceId);
        if (cluster == null) {
            throw new IllegalArgumentException("数据源不存在");
        }
        try (Connection ignored = connectionFactory.open(cluster.getSourceType(), ConnectionConfig.from(cluster), null,
                account.getUsername(), account.getPassword(), "AGENT".equals(account.getSubjectType()))) {
            if (account.getId() == null) {
                accountMapper.insert(account);
            } else {
                accountMapper.updateById(account);
            }
            return account;
        } catch (SQLException e) {
            throw new IllegalArgumentException("账号连接校验失败，请检查数据库账号及权限");
        }
    }

    public void delete(Long sourceId, Long id) {
        accountMapper.delete(new LambdaQueryWrapper<DatasourceSubjectAccount>()
                .eq(DatasourceSubjectAccount::getId, id).eq(DatasourceSubjectAccount::getSourceId, sourceId));
    }
}
