package com.onedata.portal.dto;

import java.util.List;
import lombok.Data;



/**
 * 字段实测取值分布 DTO。
 *
 * <p>一个字段一条：{@code values} 是按出现次数倒序的真实取值（来自目标表数据的
 * {@code GROUP BY}），供智能元数据把枚举含义建立在真实数据上，而不是让模型凭
 * DDL 与 SQL 文本猜测。取值数超过阈值的字段判定为非枚举，不会出现在返回结果里。
 */
@Data
public class ColumnValueProfile {

    /**
     * 字段名
     */
    private String fieldName;

    /**
     * 字段类型（平台元数据中的声明类型）
     */
    private String fieldType;

    /**
     * 实测去重取值数
     */
    private Integer distinctCount;

    /**
     * 按出现次数倒序的取值列表
     */
    private List<ColumnValueCount> values;

    @Data
    public static class ColumnValueCount {

        /**
         * 原始取值（统一以字符串表达，NULL 不参与统计）
         */
        private String value;

        /**
         * 出现行数
         */
        private Long count;

        public ColumnValueCount() {
        }

        public ColumnValueCount(String value, Long count) {
            this.value = value;
            this.count = count;
        }
    }
}
