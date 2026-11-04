package com.onedata.portal.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Per-section outcome of the {@code odw_update_table_metadata} tool. Writes are not transactional: each
 * section (and each field) is applied independently, so the agent can retry only what failed.
 */
@Data
public class MetadataCompleteResponse {

    private Long tableId;

    private String database;

    private String table;

    /** e.g. "table_comment", "attributes", "field:etl_time", "freshness". */
    private List<String> applied = new ArrayList<>();

    /** e.g. "attributes: 缺少有效分层", "field:ghost: 表中不存在该字段". */
    private List<String> skipped = new ArrayList<>();

    /** e.g. "freshness: <reason>". */
    private List<String> failed = new ArrayList<>();
}
