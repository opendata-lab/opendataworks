package com.onedata.portal.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.onedata.portal.entity.TableFreshnessConfig;
import org.apache.ibatis.annotations.Mapper;


/**
 * 表级新鲜度契约 Mapper
 */
@Mapper
public interface TableFreshnessConfigMapper extends BaseMapper<TableFreshnessConfig> {
}
