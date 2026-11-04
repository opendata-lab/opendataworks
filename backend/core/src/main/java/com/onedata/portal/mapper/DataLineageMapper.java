package com.onedata.portal.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.onedata.portal.entity.DataLineage;
import org.apache.ibatis.annotations.Mapper;


/**
 * 血缘关系 Mapper
 */
@Mapper
public interface DataLineageMapper extends BaseMapper<DataLineage> {
}
