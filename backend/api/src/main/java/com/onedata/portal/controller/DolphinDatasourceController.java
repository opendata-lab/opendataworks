package com.onedata.portal.controller;

import com.onedata.portal.dto.DolphinDatasourceOption;
import com.onedata.portal.dto.Result;
import com.onedata.portal.service.DataTaskService;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * DolphinScheduler 数据源相关接口
 */
@RestController
@RequestMapping("/v1/dolphin")
@RequiredArgsConstructor
public class DolphinDatasourceController {

    private final DataTaskService dataTaskService;

    /**
     * 查询可用的 Dolphin 数据源
     */
    @GetMapping("/datasources")
    public Result<List<DolphinDatasourceOption>> listDatasources(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long dolphinConfigId) {
        String filterType;
        if ("ALL".equalsIgnoreCase(type)) {
            filterType = null;
        } else {
            filterType = type;
        }
        List<DolphinDatasourceOption> options =
                dataTaskService.listDatasourceOptions(filterType, keyword, dolphinConfigId);
        return Result.success(options);
    }
}
