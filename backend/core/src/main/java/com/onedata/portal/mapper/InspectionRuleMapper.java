package com.onedata.portal.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.onedata.portal.entity.InspectionRule;
import org.apache.ibatis.annotations.Mapper;


/**
 * 巡检规则 Mapper
 */
@Mapper
public interface InspectionRuleMapper extends BaseMapper<InspectionRule> {
}
