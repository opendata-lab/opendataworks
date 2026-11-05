package com.onedata.portal.entity;

import com.baomidou.mybatisplus.annotation.*;

import lombok.Data;

@Data
@TableName("platform_user_operation")
public class PlatformUserOperation {
    @TableId(type = IdType.AUTO)
    private Long id;

    private String operatorId;
    private Long userId;
    private String action;
    private String detail;
}
