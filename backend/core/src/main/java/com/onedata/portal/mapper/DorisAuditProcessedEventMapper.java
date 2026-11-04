package com.onedata.portal.mapper;

import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;



@Mapper
public interface DorisAuditProcessedEventMapper {

    @Insert("INSERT IGNORE INTO doris_audit_processed_event "
            + "(cluster_id, event_key, event_time, processed_at) "
            + "VALUES (#{clusterId}, #{eventKey}, #{eventTime}, NOW())")
    int insertIgnore(@Param("clusterId") Long clusterId,
            @Param("eventKey") String eventKey,
            @Param("eventTime") LocalDateTime eventTime);

    @Delete("DELETE FROM doris_audit_processed_event WHERE event_time < #{threshold}")
    int deleteBefore(@Param("threshold") LocalDateTime threshold);
}
