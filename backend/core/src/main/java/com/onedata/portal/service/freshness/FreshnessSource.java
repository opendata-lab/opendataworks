package com.onedata.portal.service.freshness;



/**
 * 契约字段来源，用于接口回显「这个值从哪来」。
 */
public enum FreshnessSource {

    /** 表级 {@code table_freshness_config} */
    TABLE,

    /** 规则配置 {@code rule_config.defaults[]} */
    RULE_DEFAULT
}
