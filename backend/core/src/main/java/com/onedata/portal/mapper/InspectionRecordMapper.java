package com.onedata.portal.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.onedata.portal.entity.InspectionRecord;
import org.apache.ibatis.annotations.Mapper;


/**
 * 巡检记录 Mapper
 */
@Mapper
public interface InspectionRecordMapper extends BaseMapper<InspectionRecord> {
}
