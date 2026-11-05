package com.onedata.portal.dto;

import java.time.LocalDateTime;
import lombok.Data;



/**
 * 单表访问汇总查询投影。
 */
@Data
public class TableAccessAggregate {

    private Long totalAccessCount;
    private Long recentAccessCount;
    private Long accessCount7d;
    private Long accessCount30d;
    private Long durationSumMs;
    private Long durationSampleCount;
    private LocalDateTime firstAccessTime;
    private LocalDateTime lastAccessTime;
}
