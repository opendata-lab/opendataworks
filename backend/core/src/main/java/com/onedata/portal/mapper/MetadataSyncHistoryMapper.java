package com.onedata.portal.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.onedata.portal.entity.MetadataSyncHistory;
import org.apache.ibatis.annotations.Mapper;


/**
 * 元数据同步历史 Mapper
 */
@Mapper
public interface MetadataSyncHistoryMapper extends BaseMapper<MetadataSyncHistory> {
}
