package com.onedata.portal.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.onedata.portal.entity.SysConfig;
import org.apache.ibatis.annotations.Mapper;


/**
 * 平台级通用配置 Mapper
 */
@Mapper
public interface SysConfigMapper extends BaseMapper<SysConfig> {
}
