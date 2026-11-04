package com.onedata.portal.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.onedata.portal.entity.DataField;
import org.apache.ibatis.annotations.Mapper;


/**
 * 字段定义 Mapper
 */
@Mapper
public interface DataFieldMapper extends BaseMapper<DataField> {
}
