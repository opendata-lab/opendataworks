package com.onedata.portal.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Data;

@Data
public class FieldMetadataDto {

    @JsonProperty("field_name")
    private String fieldName;

    @JsonProperty("field_type")
    private String fieldType;

    @JsonProperty("field_comment")
    private String fieldComment;
}
