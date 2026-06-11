package com.onedata.portal.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.onedata.portal.entity.DataTableVersion;
import org.apache.ibatis.annotations.Mapper;

/**
 * 表元数据版本 Mapper
 */
@Mapper
public interface DataTableVersionMapper extends BaseMapper<DataTableVersion> {
}
