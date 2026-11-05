package com.onedata.portal.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.onedata.portal.entity.SysConfig;
import com.onedata.portal.mapper.SysConfigMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;


/**
 * 平台级通用键值配置读写。
 *
 * <p>按 {@code configKey} upsert；值为空串表示"未配置"，读取时统一返回空串，
 * 调用方不必区分「行不存在」与「值为空」。
 */
@Service
@RequiredArgsConstructor
public class SysConfigService {

    private final SysConfigMapper sysConfigMapper;

    public String get(String configKey) {
        SysConfig config = findByKey(configKey);
        return config == null ? "" : String.valueOf(config.getConfigValue() == null ? "" : config.getConfigValue());
    }

    public void set(String configKey, String configValue, String description) {
        if (!StringUtils.hasText(configKey)) {
            throw new IllegalArgumentException("配置键不能为空");
        }
        String value = configValue == null ? "" : configValue.trim();

        SysConfig existing = findByKey(configKey);
        if (existing == null) {
            SysConfig created = new SysConfig();
            created.setConfigKey(configKey.trim());
            created.setConfigValue(value);
            created.setDescription(description);
            sysConfigMapper.insert(created);
            return;
        }
        existing.setConfigValue(value);
        if (StringUtils.hasText(description)) {
            existing.setDescription(description);
        }
        sysConfigMapper.updateById(existing);
    }

    private SysConfig findByKey(String configKey) {
        if (!StringUtils.hasText(configKey)) {
            return null;
        }
        LambdaQueryWrapper<SysConfig> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SysConfig::getConfigKey, configKey.trim()).last("LIMIT 1");
        return sysConfigMapper.selectOne(wrapper);
    }
}
