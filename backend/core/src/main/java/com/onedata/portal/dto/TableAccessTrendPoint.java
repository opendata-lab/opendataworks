package com.onedata.portal.dto;

import lombok.Data;


/**
 * 表访问趋势点
 */
@Data
public class TableAccessTrendPoint {

    /**
     * 日期（yyyy-MM-dd）
     */
    private String date;

    /**
     * 访问次数
     */
    private Long accessCount;
}
