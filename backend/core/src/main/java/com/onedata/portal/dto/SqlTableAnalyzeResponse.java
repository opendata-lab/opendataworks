package com.onedata.portal.dto;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;



/**
 * SQL 表名解析结果（增强版）
 */
@Data
public class SqlTableAnalyzeResponse {

    private List<TableRefMatch> inputRefs = new ArrayList<>();

    private List<TableRefMatch> outputRefs = new ArrayList<>();

    private List<String> unmatched = new ArrayList<>();

    private List<String> ambiguous = new ArrayList<>();

    @Data
    public static class TableRefMatch {
        private String rawName;
        private String database;
        private String tableName;
        private String matchStatus; // matched / ambiguous / unmatched
        private Double confidence;
        private TableCandidate chosenTable;
        private List<TableCandidate> candidates = new ArrayList<>();
        private List<Span> spans = new ArrayList<>();
    }

    @Data
    public static class TableCandidate {
        private Long tableId;
        private Long clusterId;
        private String clusterName;
        private String sourceType;
        private String dbName;
        private String tableName;
        private String tableComment;
    }

    @Data
    public static class Span {
        private Integer from;
        private Integer to;

        public Span() {
        }

        public Span(Integer from, Integer to) {
            this.from = from;
            this.to = to;
        }
    }
}
