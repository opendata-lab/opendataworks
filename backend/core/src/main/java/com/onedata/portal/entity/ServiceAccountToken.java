package com.onedata.portal.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.*;

import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("service_account_token")
public class ServiceAccountToken {
    @TableId(type = IdType.AUTO)
    private Long id;

    @JsonIgnore private Long userId;
    private String name;
    @JsonIgnore @lombok.ToString.Exclude private String tokenHash;

    @JsonProperty("prefix")
    private String tokenPrefix;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'")
    private LocalDateTime expiresAt;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'")
    private LocalDateTime revokedAt;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'")
    private LocalDateTime lastUsedAt;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'")
    private LocalDateTime createdAt;

    @JsonIgnore private String createdBy;
}
