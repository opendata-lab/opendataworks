package com.onedata.portal.controller;

import com.onedata.portal.dto.Result;
import com.onedata.portal.service.LineageService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 血缘关系 Controller
 */
@RestController
@RequestMapping("/v1/lineage")
@RequiredArgsConstructor
public class LineageController {

    private final LineageService lineageService;

    /**
     * 获取血缘图数据
     */
    @GetMapping
    public Result<LineageService.LineageGraph> getLineageGraph(
        @RequestParam(required = false) String layer,
        @RequestParam(required = false) String businessDomain,
        @RequestParam(required = false) String dataDomain,
        @RequestParam(required = false) String keyword,
        @RequestParam(required = false) Long clusterId,
        @RequestParam(required = false) String dbName,
        @RequestParam(required = false) Long tableId,
        @RequestParam(required = false) Integer depth
    ) {
        return Result.success(lineageService.getLineageGraph(
                layer, businessDomain, dataDomain, keyword, clusterId, dbName, tableId, depth));
    }
}
