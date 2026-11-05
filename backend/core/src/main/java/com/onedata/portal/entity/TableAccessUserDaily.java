package com.onedata.portal.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Data;



@Data
@TableName("table_access_user_daily")
public class TableAccessUserDaily {

    private Long clusterId;
    private LocalDate accessDate;
    private String dbName;
    private String tableName;
    private String userName;
    private Long accessCount;
    private LocalDateTime lastAccessTime;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
