package com.onedata.portal.controller;

import com.onedata.portal.dto.Result;
import com.onedata.portal.dto.SqlTableAnalyzeResponse;
import com.onedata.portal.dto.SqlTableMatchRequest;
import com.onedata.portal.service.SqlTableMatcherService;

import lombok.RequiredArgsConstructor;

import org.springframework.web.bind.annotation.*;

/** SQL 表名匹配 Controller */
@RestController
@RequestMapping("/v1/sql-table-matcher")
@RequiredArgsConstructor
public class SqlTableMatcherController {

    private final SqlTableMatcherService sqlTableMatcherService;

    @PostMapping("/analyze")
    public Result<SqlTableAnalyzeResponse> analyze(@RequestBody SqlTableMatchRequest request) {
        return Result.success(
                sqlTableMatcherService.analyze(request.getSql(), request.getNodeType()));
    }
}
