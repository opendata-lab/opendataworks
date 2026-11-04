package com.onedata.portal.dto;

import java.util.List;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;



/**
 * 查询结果预览
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class QueryPreview {
    private List<String> columns;
    private List<Map<String, Object>> rows;
}
