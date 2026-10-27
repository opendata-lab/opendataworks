package com.onedata.portal.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.ToString;

@Data
@TableName("datasource_subject_account")
public class DatasourceSubjectAccount {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long sourceId;
    private String subjectType;
    private String subjectId;
    private String username;
    @ToString.Exclude
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String password;
}
