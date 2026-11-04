package com.onedata.portal.dto.dolphin;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import lombok.Data;


/**
 * Paginated list response from DolphinScheduler.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class DolphinPageData<T> {

    private List<T> totalList;
    private int total;
    private int totalPage;
    private int pageSize;
    private int currentPage;
    private int start;
}
