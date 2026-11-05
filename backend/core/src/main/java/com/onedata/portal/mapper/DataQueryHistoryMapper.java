package com.onedata.portal.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.onedata.portal.entity.DataQueryHistory;
import org.apache.ibatis.annotations.Mapper;


/**
 * SQL 查询历史 Mapper
 */
@Mapper
public interface DataQueryHistoryMapper extends BaseMapper<DataQueryHistory> {
}
