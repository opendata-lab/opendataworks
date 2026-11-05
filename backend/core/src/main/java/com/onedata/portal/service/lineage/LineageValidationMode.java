package com.onedata.portal.service.lineage;



/**
 * 任务保存时的血缘校验强度。
 */
public enum LineageValidationMode {

    /**
     * 只校验最终输出表非空。平台 UI、运行态同步、版本回滚使用。
     */
    LENIENT,

    /**
     * 追加 SQL 高可信缺失校验：SQL 已明确匹配出的表必须出现在最终血缘中。
     * Agent 写入路径使用。
     */
    STRICT
}
