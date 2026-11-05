package com.onedata.portal.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.onedata.portal.entity.DolphinConfig;
import com.onedata.portal.mapper.DolphinConfigMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Service for managing DolphinScheduler configuration. */
@Slf4j
@Service
@RequiredArgsConstructor
public class DolphinConfigService {

    private static final String DEFAULT_TENANT_CODE = "default";
    private static final String DEFAULT_WORKER_GROUP = "default";
    private static final String DEFAULT_EXECUTION_TYPE = "PARALLEL";

    private final DolphinConfigMapper dolphinConfigMapper;
    private final RuntimeBindingLock runtimeBindingLock;

    /**
     * Get the active DolphinScheduler configuration. Returns null if no active config exists.
     *
     * <p>这里刻意不做进程内缓存。此前用一个 volatile 字段缓存配置对象，而失效发生在事务内部：
     * 清空之后、事务提交之前，其他线程会从库里读到旧数据把缓存重新填上，事务提交后又没有第二次 失效，于是缓存可能长期停留在旧 URL / 旧默认环境上。配置查询本身很轻，而它的每个调用点
     * 紧接着都是一次 Dolphin HTTP 调用，省这一次 SELECT 换来的是难以察觉的错配。
     */
    public DolphinConfig getActiveConfig() {
        return getDefaultConfig();
    }

    /** Get config for editing (can be same as active). */
    public List<DolphinConfig> listAll(Boolean activeOnly) {
        QueryWrapper<DolphinConfig> wrapper = new QueryWrapper<>();
        if (Boolean.TRUE.equals(activeOnly)) {
            wrapper.eq("is_active", true);
        }
        wrapper.orderByDesc("is_default").orderByAsc("config_name").orderByAsc("id");
        return dolphinConfigMapper.selectList(wrapper);
    }

    public DolphinConfig getById(Long id) {
        return id == null ? null : dolphinConfigMapper.selectById(id);
    }

    public DolphinConfig getEnabledConfig(Long id) {
        DolphinConfig config = id == null ? getDefaultConfig() : dolphinConfigMapper.selectById(id);
        if (config == null) {
            throw new IllegalArgumentException("Dolphin 环境不存在");
        }
        if (!Boolean.TRUE.equals(config.getIsActive())) {
            throw new IllegalStateException("Dolphin 环境未启用: " + displayName(config));
        }
        return config;
    }

    public DolphinConfig getDefaultConfig() {
        DolphinConfig config =
                dolphinConfigMapper.selectOne(
                        new QueryWrapper<DolphinConfig>()
                                .eq("is_default", 1)
                                .eq("is_active", true)
                                .orderByDesc("id")
                                .last("LIMIT 1"));
        if (config != null) {
            return config;
        }
        return dolphinConfigMapper.selectOne(
                new QueryWrapper<DolphinConfig>()
                        .eq("is_active", true)
                        .orderByDesc("id")
                        .last("LIMIT 1"));
    }

    @Transactional
    public DolphinConfig create(DolphinConfig config) {
        // 新建时若直接设为默认，同样会改变空配置工作流的实际指向
        runtimeBindingLock.acquire();
        pinResidualRuntimeBindings();
        normalize(config, true, null);
        if (Integer.valueOf(1).equals(config.getIsDefault())) {
            resetDefault(null);
        }
        dolphinConfigMapper.insert(config);
        log.info("Created DolphinScheduler configuration: {}", config.getConfigName());
        return config;
    }

    @Transactional
    public DolphinConfig update(Long id, DolphinConfig config) {
        // "统计绑定数量 -> 写入" 是读改写：不加锁的话，并发导入可以在这里读到"尚未绑定"
        // 之后才提交绑定，运行态身份就会在无人复查的情况下被改掉。
        runtimeBindingLock.acquire();
        pinResidualRuntimeBindings();
        DolphinConfig existing = dolphinConfigMapper.selectById(id);
        if (existing == null) {
            throw new IllegalArgumentException("Dolphin 环境不存在");
        }
        boolean runtimeBound = countRuntimeBoundWorkflows(id) > 0;
        normalize(config, false, existing);
        if (runtimeBound && runtimeIdentityChanged(existing, config)) {
            throw new IllegalStateException("Dolphin 环境已被运行态工作流绑定，不能修改服务地址或项目名称");
        }
        config.setId(id);
        if (Integer.valueOf(1).equals(config.getIsDefault())) {
            resetDefault(id);
        }
        dolphinConfigMapper.updateById(config);
        log.info("Updated DolphinScheduler configuration: {}({})", id, config.getConfigName());
        return dolphinConfigMapper.selectById(id);
    }

    @Transactional
    public void delete(Long id) {
        // 同 update：与并发导入的运行态绑定互斥，否则会删掉一个刚被绑定上的环境
        runtimeBindingLock.acquire();
        pinResidualRuntimeBindings();
        DolphinConfig existing = dolphinConfigMapper.selectById(id);
        if (existing == null) {
            return;
        }
        if (countRuntimeBoundWorkflows(id) > 0) {
            throw new IllegalStateException("Dolphin 环境已被运行态工作流绑定，不能删除");
        }
        dolphinConfigMapper.deleteById(id);
        log.info("Deleted DolphinScheduler configuration: {}({})", id, displayName(existing));
    }

    @Transactional
    public void setDefault(Long id) {
        // 切换默认环境会连带改变所有 dolphin_config_id 为空的工作流实际指向的 Dolphin，
        // 因此同样要进入运行态绑定的互斥协议，且必须在第一次读库之前取锁
        runtimeBindingLock.acquire();
        pinResidualRuntimeBindings();
        DolphinConfig existing = dolphinConfigMapper.selectById(id);
        if (existing == null) {
            throw new IllegalArgumentException("Dolphin 环境不存在");
        }
        if (!Boolean.TRUE.equals(existing.getIsActive())) {
            throw new IllegalStateException("停用的 Dolphin 环境不能设为默认");
        }
        resetDefault(id);
        existing.setIsDefault(1);
        dolphinConfigMapper.updateById(existing);
    }

    /**
     * Update the DolphinScheduler configuration. Implementation: Update the existing active record
     * or insert a new one if none exists. For simplicity, we maintain a single active record.
     */
    private void normalize(DolphinConfig config, boolean requireToken, DolphinConfig existing) {
        if (config == null) {
            throw new IllegalArgumentException("Dolphin 配置不能为空");
        }
        if (!StringUtils.hasText(config.getConfigName())) {
            config.setConfigName(
                    existing != null && StringUtils.hasText(existing.getConfigName())
                            ? existing.getConfigName()
                            : "Dolphin");
        } else {
            config.setConfigName(config.getConfigName().trim());
        }
        if (!StringUtils.hasText(config.getUrl())) {
            throw new IllegalArgumentException("Dolphin 服务地址不能为空");
        }
        config.setUrl(trimTrailingSlash(config.getUrl()));
        if (!StringUtils.hasText(config.getToken())) {
            if (existing != null && StringUtils.hasText(existing.getToken())) {
                config.setToken(existing.getToken());
            } else if (requireToken) {
                throw new IllegalArgumentException("Dolphin 访问令牌不能为空");
            }
        } else {
            config.setToken(config.getToken().trim());
        }
        if (!StringUtils.hasText(config.getProjectName())) {
            throw new IllegalArgumentException("Dolphin 项目名称不能为空");
        }
        config.setProjectName(config.getProjectName().trim());
        if (StringUtils.hasText(config.getProjectCode())) {
            config.setProjectCode(config.getProjectCode().trim());
        } else if (existing != null) {
            config.setProjectCode(existing.getProjectCode());
        }
        config.setTenantCode(defaultText(config.getTenantCode(), DEFAULT_TENANT_CODE));
        config.setWorkerGroup(defaultText(config.getWorkerGroup(), DEFAULT_WORKER_GROUP));
        config.setExecutionType(
                defaultText(config.getExecutionType(), DEFAULT_EXECUTION_TYPE)
                        .toUpperCase(Locale.ROOT));
        if (config.getIsActive() == null) {
            config.setIsActive(existing == null ? Boolean.TRUE : existing.getIsActive());
        }
        if (config.getIsDefault() == null) {
            config.setIsDefault(existing == null ? 0 : existing.getIsDefault());
        } else {
            config.setIsDefault(config.getIsDefault() == 1 ? 1 : 0);
        }
        if (StringUtils.hasText(config.getDescription())) {
            config.setDescription(config.getDescription().trim());
        }
        if (config.getDeleted() == null) {
            config.setDeleted(existing == null ? 0 : existing.getDeleted());
        }
    }

    /**
     * 统计绑定到该环境的运行态工作流。若它是当前默认环境，还要算上 {@code dolphin_config_id} 为空的存量行 ——
     * 那些工作流实际就跟着默认环境跑，漏算的话默认环境会被随意改身份或删除， 把它们静默指向另一套 Dolphin。
     */
    private long countRuntimeBoundWorkflows(Long id) {
        Long count = dolphinConfigMapper.countRuntimeBoundWorkflows(id);
        long total = count == null ? 0L : count;
        if (isCurrentDefault(id)) {
            Long orphan = dolphinConfigMapper.countRuntimeBoundWorkflowsWithoutConfig();
            total += orphan == null ? 0L : orphan;
        }
        return total;
    }

    /**
     * 在改动任何配置之前，把仍未绑定环境的运行态工作流固定到它们当下实际使用的环境。
     *
     * <p>互斥只能挡住并发，挡不住"先切默认、后续发布就落到新环境"这种顺序漂移。 这些工作流本来就跟着 {@link #getDefaultConfig()} 解析出的有效默认环境跑，
     * 在切换前把这层隐式归属落成显式归属，切换之后它们才不会跟着漂走。
     *
     * <p>注意用的是"有效默认环境"而不是 {@code is_default = 1}：没有显式默认时运行时会回落到 最新的启用配置，只认显式标记会漏掉这种状态下的工作流。
     */
    private void pinResidualRuntimeBindings() {
        DolphinConfig effectiveDefault = getDefaultConfig();
        if (effectiveDefault == null || effectiveDefault.getId() == null) {
            return;
        }
        int pinned =
                dolphinConfigMapper.pinRuntimeBoundWorkflowsWithoutConfig(effectiveDefault.getId());
        if (pinned > 0) {
            log.info(
                    "Pinned {} runtime-bound workflows to dolphin config {}",
                    pinned,
                    effectiveDefault.getId());
        }
    }

    private boolean isCurrentDefault(Long id) {
        if (id == null) {
            return false;
        }
        DolphinConfig current = getDefaultConfig();
        return current != null && Objects.equals(current.getId(), id);
    }

    private boolean runtimeIdentityChanged(DolphinConfig existing, DolphinConfig next) {
        return !Objects.equals(
                        trimTrailingSlash(existing.getUrl()), trimTrailingSlash(next.getUrl()))
                || !Objects.equals(existing.getProjectName(), next.getProjectName());
    }

    private void resetDefault(Long excludeId) {
        UpdateWrapper<DolphinConfig> wrapper =
                new UpdateWrapper<DolphinConfig>().set("is_default", 0);
        if (excludeId != null) {
            wrapper.ne("id", excludeId);
        }
        dolphinConfigMapper.update(null, wrapper);
    }

    private String defaultText(String value, String defaultValue) {
        return StringUtils.hasText(value) ? value.trim() : defaultValue;
    }

    private String trimTrailingSlash(String value) {
        if (!StringUtils.hasText(value)) {
            return value;
        }
        String normalized = value.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private String displayName(DolphinConfig config) {
        if (config == null) {
            return "-";
        }
        return StringUtils.hasText(config.getConfigName())
                ? config.getConfigName()
                : String.valueOf(config.getId());
    }

    public DolphinConfig resolveActiveOrTargetConfig(Long id) {
        DolphinConfig current = getEnabledConfig(id);
        DolphinConfig snapshot = new DolphinConfig();
        org.springframework.beans.BeanUtils.copyProperties(current, snapshot);
        return snapshot;
    }
}
