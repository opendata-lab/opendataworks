package com.onedata.portal.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.onedata.portal.entity.MinioConfig;
import com.onedata.portal.mapper.MinioConfigMapper;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;



/**
 * MinIO 环境配置管理
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MinioConfigService {

    private static final String DEFAULT_REGION = "us-east-1";

    private final MinioConfigMapper minioConfigMapper;

    public List<MinioConfig> listAll(String status) {
        LambdaQueryWrapper<MinioConfig> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(status)) {
            wrapper.eq(MinioConfig::getStatus, status.trim().toLowerCase(Locale.ROOT));
        }
        wrapper.orderByDesc(MinioConfig::getIsDefault)
                .orderByAsc(MinioConfig::getConfigName);
        return minioConfigMapper.selectList(wrapper);
    }

    public MinioConfig getById(Long id) {
        return minioConfigMapper.selectById(id);
    }

    public MinioConfig getEnabledById(Long id) {
        if (id == null) {
            return null;
        }
        MinioConfig config = minioConfigMapper.selectById(id);
        if (config == null) {
            return null;
        }
        if (!"active".equalsIgnoreCase(config.getStatus())) {
            throw new IllegalStateException("MinIO 环境未启用: " + config.getConfigName());
        }
        return config;
    }

    @Transactional
    public MinioConfig create(MinioConfig config) {
        validate(config, true);
        handleDefault(config, null);
        minioConfigMapper.insert(config);
        log.info("Created MinIO config: {}", config.getConfigName());
        return config;
    }

    @Transactional
    public MinioConfig update(Long id, MinioConfig config) {
        MinioConfig exists = minioConfigMapper.selectById(id);
        if (exists == null) {
            throw new IllegalArgumentException("MinIO 环境不存在");
        }
        if (!StringUtils.hasText(config.getSecretKey())) {
            config.setSecretKey(exists.getSecretKey());
        }
        if (config.getUsePathStyle() == null) {
            config.setUsePathStyle(exists.getUsePathStyle());
        }
        if (!StringUtils.hasText(config.getStatus())) {
            config.setStatus(exists.getStatus());
        }
        if (config.getIsDefault() == null) {
            config.setIsDefault(exists.getIsDefault());
        }
        validate(config, false);
        config.setId(id);
        handleDefault(config, id);
        minioConfigMapper.updateById(config);
        log.info("Updated MinIO config: {}({})", id, config.getConfigName());
        return minioConfigMapper.selectById(id);
    }

    @Transactional
    public void delete(Long id) {
        MinioConfig exists = minioConfigMapper.selectById(id);
        if (exists == null) {
            return;
        }
        minioConfigMapper.deleteById(id);
        log.info("Deleted MinIO config: {}({})", id, exists.getConfigName());
    }

    @Transactional
    public void setDefault(Long id) {
        MinioConfig exists = minioConfigMapper.selectById(id);
        if (exists == null) {
            throw new IllegalArgumentException("MinIO 环境不存在");
        }
        resetDefault(id);
        exists.setIsDefault(1);
        minioConfigMapper.updateById(exists);
    }

    private void validate(MinioConfig config, boolean requireSecret) {
        if (config == null) {
            throw new IllegalArgumentException("MinIO 配置不能为空");
        }
        if (!StringUtils.hasText(config.getConfigName())) {
            throw new IllegalArgumentException("环境名称不能为空");
        }
        config.setConfigName(config.getConfigName().trim());
        if (!StringUtils.hasText(config.getEndpoint())) {
            throw new IllegalArgumentException("endpoint 不能为空");
        }
        config.setEndpoint(trimTrailingSlash(config.getEndpoint()));

        if (!StringUtils.hasText(config.getAccessKey())) {
            throw new IllegalArgumentException("accessKey 不能为空");
        }
        config.setAccessKey(config.getAccessKey().trim());

        if (requireSecret && !StringUtils.hasText(config.getSecretKey())) {
            throw new IllegalArgumentException("secretKey 不能为空");
        }
        if (StringUtils.hasText(config.getSecretKey())) {
            config.setSecretKey(config.getSecretKey().trim());
        }

        if (config.getUsePathStyle() == null) {
            config.setUsePathStyle(1);
        } else {
            config.setUsePathStyle(config.getUsePathStyle() == 0 ? 0 : 1);
        }
        if (!StringUtils.hasText(config.getRegion())) {
            config.setRegion(DEFAULT_REGION);
        } else {
            config.setRegion(config.getRegion().trim());
        }
        if (!StringUtils.hasText(config.getStatus())) {
            config.setStatus("active");
        } else {
            String normalizedStatus = config.getStatus().trim().toLowerCase(Locale.ROOT);
            if (!"active".equals(normalizedStatus) && !"inactive".equals(normalizedStatus)) {
                throw new IllegalArgumentException("status 仅支持 active / inactive");
            }
            config.setStatus(normalizedStatus);
        }
        if (config.getIsDefault() == null) {
            config.setIsDefault(0);
        } else {
            config.setIsDefault(config.getIsDefault() == 1 ? 1 : 0);
        }
    }

    private void handleDefault(MinioConfig config, Long currentId) {
        if (config.getIsDefault() != null && config.getIsDefault() == 1) {
            resetDefault(currentId);
        }
    }

    private void resetDefault(Long excludeId) {
        LambdaUpdateWrapper<MinioConfig> wrapper = new LambdaUpdateWrapper<MinioConfig>()
                .set(MinioConfig::getIsDefault, 0);
        if (excludeId != null) {
            wrapper.ne(MinioConfig::getId, excludeId);
        }
        minioConfigMapper.update(null, wrapper);
    }

    private String trimTrailingSlash(String endpoint) {
        String normalized = endpoint.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }
}

