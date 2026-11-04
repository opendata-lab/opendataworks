package com.onedata.portal.dto;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;



/**
 * 分页结果
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PageResult<T> {

    private Long total;
    private List<T> records;

    public static <T> PageResult<T> of(Long total, List<T> records) {
        return new PageResult<>(total, records);
    }
}
