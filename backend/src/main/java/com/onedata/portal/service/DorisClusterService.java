package com.onedata.portal.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.onedata.portal.entity.DorisCluster;
import com.onedata.portal.mapper.DorisClusterMapper;
import com.onedata.portal.service.datasource.core.DatasourceAdapterRegistry;
import com.onedata.portal.service.datasource.model.ConnectionConfig;
import com.onedata.portal.service.datasource.model.DatasourceDescriptor;
import com.onedata.portal.util.JdbcUrlParts;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Doris 集群配置服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DorisClusterService {

    private final DorisClusterMapper dorisClusterMapper;
    private final DatasourceAdapterRegistry adapterRegistry;

    /**
     * 查询所有集群
     */
    public List<DorisCluster> listAll() {
        return describe(dorisClusterMapper.selectList(
                new LambdaQueryWrapper<DorisCluster>()
                        .orderByDesc(DorisCluster::getIsDefault)
                        .orderByAsc(DorisCluster::getClusterName)));
    }

    /**
     * 根据ID获取集群
     */
    public DorisCluster getById(Long id) {
        return describe(dorisClusterMapper.selectById(id));
    }

    /**
     * 根据名称获取集群
     */
    public DorisCluster getByName(String name) {
        return dorisClusterMapper.selectOne(
                new LambdaQueryWrapper<DorisCluster>()
                        .eq(DorisCluster::getClusterName, name)
                        .last("LIMIT 1"));
    }

    /**
     * 创建集群
     */
    @Transactional
    public DorisCluster create(DorisCluster cluster) {
        validate(cluster);
        handleDefaultFlag(cluster, null);
        if (cluster.getAutoSync() != null && cluster.getAutoSync() == 1) {
            cluster.setLastSyncTime(LocalDateTime.now());
        }
        dorisClusterMapper.insert(cluster);
        log.info("Created Doris cluster: {}", cluster.getClusterName());
        return describe(cluster);
    }

    /**
     * 更新集群
     */
    @Transactional
    public DorisCluster update(Long id, DorisCluster cluster) {
        DorisCluster exists = dorisClusterMapper.selectById(id);
        if (exists == null) {
            throw new RuntimeException("Doris 集群不存在");
        }
        if (!StringUtils.hasText(cluster.getPassword())) {
            cluster.setPassword(exists.getPassword());
        }
        if (!StringUtils.hasText(cluster.getSourceType())) {
            cluster.setSourceType(exists.getSourceType());
        }
        if (cluster.getDatabaseName() == null) {
            cluster.setDatabaseName(exists.getDatabaseName());
        }
        Map<String, Object> options = cluster.getConnectionOptions() == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(cluster.getConnectionOptions());
        if (exists.getConnectionOptions() != null
                && DatasourceAdapterRegistry.normalize(exists.getSourceType()).equals(
                        DatasourceAdapterRegistry.normalize(cluster.getSourceType()))) {
            for (DatasourceDescriptor.ConnectionField field
                    : adapterRegistry.require(cluster.getSourceType()).descriptor().getConnectionFields()) {
                if ("password".equals(field.getControl())
                        && (!options.containsKey(field.getKey()) || "".equals(options.get(field.getKey())))) {
                    if (exists.getConnectionOptions().containsKey(field.getKey())) {
                        options.put(field.getKey(), exists.getConnectionOptions().get(field.getKey()));
                    }
                }
            }
        }
        if (cluster.getConnectionOptions() == null && exists.getConnectionOptions() != null) {
            options.putAll(exists.getConnectionOptions());
        }
        cluster.setConnectionOptions(options);
        if (cluster.getAutoSync() == null) {
            cluster.setAutoSync(exists.getAutoSync());
        }
        if (cluster.getSyncCron() == null) {
            cluster.setSyncCron(exists.getSyncCron());
        }
        if (cluster.getLastSyncTime() == null) {
            cluster.setLastSyncTime(exists.getLastSyncTime());
        }

        if ((exists.getAutoSync() == null || exists.getAutoSync() != 1) && cluster.getAutoSync() != null
                && cluster.getAutoSync() == 1) {
            // 开启自动同步时，将 lastSyncTime 置为当前时间作为调度基准
            cluster.setLastSyncTime(LocalDateTime.now());
        }
        validate(cluster);
        cluster.setId(id);
        handleDefaultFlag(cluster, id);
        dorisClusterMapper.updateById(cluster);
        log.info("Updated Doris cluster: {}", cluster.getClusterName());
        return describe(dorisClusterMapper.selectById(id));
    }

    /**
     * 删除集群
     */
    @Transactional
    public void delete(Long id) {
        dorisClusterMapper.deleteById(id);
        log.info("Deleted Doris cluster: {}", id);
    }

    /**
     * 设置默认集群
     */
    @Transactional
    public void setDefault(Long id) {
        DorisCluster exists = dorisClusterMapper.selectById(id);
        if (exists == null) {
            throw new RuntimeException("Doris 集群不存在");
        }

        resetDefaultFlag(id);

        exists.setIsDefault(1);
        dorisClusterMapper.updateById(exists);
        log.info("Set default Doris cluster: {}", id);
    }

    private void validate(DorisCluster cluster) {
        if (cluster == null) {
            throw new IllegalArgumentException("Doris 集群不能为空");
        }
        if (!StringUtils.hasText(cluster.getClusterName())) {
            throw new RuntimeException("集群名称不能为空");
        }
        if (!StringUtils.hasText(cluster.getSourceType())) {
            cluster.setSourceType("DORIS");
        } else {
            cluster.setSourceType(cluster.getSourceType().trim().toUpperCase(Locale.ROOT));
        }
        if (StringUtils.hasText(cluster.getDatabaseName())) {
            cluster.setDatabaseName(cluster.getDatabaseName().trim());
        }
        adapterRegistry.validate(cluster.getSourceType(), ConnectionConfig.from(cluster));
        cluster.setFeHost(JdbcUrlParts.requireHost(cluster.getFeHost()));
        if (cluster.getPassword() == null) {
            cluster.setPassword("");
        }
        if (cluster.getIsDefault() == null) {
            cluster.setIsDefault(0);
        }
        if (!StringUtils.hasText(cluster.getStatus())) {
            cluster.setStatus("active");
        }
        if (cluster.getAutoSync() == null) {
            cluster.setAutoSync(0);
        }
        if (cluster.getAutoSync() == 1) {
            if (!StringUtils.hasText(cluster.getSyncCron())) {
                throw new RuntimeException("开启自动同步时必须配置同步 Cron");
            }
            try {
                CronExpression.parse(cluster.getSyncCron().trim());
            } catch (Exception e) {
                throw new RuntimeException("同步 Cron 表达式不合法: " + e.getMessage());
            }
            cluster.setSyncCron(cluster.getSyncCron().trim());
        } else if (StringUtils.hasText(cluster.getSyncCron())) {
            cluster.setSyncCron(cluster.getSyncCron().trim());
        }
    }

    private DorisCluster describe(DorisCluster cluster) {
        if (cluster != null) cluster.setTypeDefinition(adapterRegistry.definition(cluster.getSourceType()));
        return cluster;
    }
    private List<DorisCluster> describe(List<DorisCluster> clusters) {
        clusters.forEach(this::describe);
        return clusters;
    }

    private void handleDefaultFlag(DorisCluster cluster, Long currentId) {
        if (cluster.getIsDefault() != null && cluster.getIsDefault() == 1) {
            resetDefaultFlag(currentId);
        }
    }

    private void resetDefaultFlag(Long excludeId) {
        LambdaUpdateWrapper<DorisCluster> updateWrapper = new LambdaUpdateWrapper<DorisCluster>()
                .set(DorisCluster::getIsDefault, 0);
        if (excludeId != null) {
            updateWrapper.ne(DorisCluster::getId, excludeId);
        }
        dorisClusterMapper.update(null, updateWrapper);
    }
}
