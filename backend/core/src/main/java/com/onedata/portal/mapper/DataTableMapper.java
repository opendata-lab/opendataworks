package com.onedata.portal.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.onedata.portal.entity.DataTable;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;



/**
 * 数据表 Mapper
 */
@Mapper
public interface DataTableMapper extends BaseMapper<DataTable> {

    @Select("SELECT * FROM data_table " +
            "WHERE deleted = 0 " +
            "AND (status IS NULL OR status <> 'deprecated') " +
            "AND db_name = #{dbName} " +
            "AND table_name = #{tableName}")
    List<DataTable> selectActiveByDbAndTable(@Param("dbName") String dbName, @Param("tableName") String tableName);

    @Select("SELECT * FROM data_table " +
            "WHERE deleted = 0 " +
            "AND (status IS NULL OR status <> 'deprecated') " +
            "AND table_name = #{tableName}")
    List<DataTable> selectActiveByTable(@Param("tableName") String tableName);
}
