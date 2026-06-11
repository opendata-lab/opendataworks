package com.onedata.portal.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 表元数据版本快照
 */
@Data
@TableName("data_table_version")
public class DataTableVersion {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long tableId;

    private Integer versionNo;

    /**
     * 规范化快照 SHA-256，用于"元数据未变化则不记新版本"的比对
     */
    private String snapshotHash;

    private String metadataSnapshot;

    private String changeSummary;

    /**
     * table_create / manual_edit / metadata_sync / inspection_fix
     */
    private String triggerSource;

    private String createdBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
