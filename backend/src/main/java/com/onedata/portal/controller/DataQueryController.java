package com.onedata.portal.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.onedata.auth.annotation.RequireAuth;
import com.onedata.auth.context.UserContextHolder;
import com.onedata.portal.dto.Result;
import com.onedata.portal.dto.SqlAnalyzeRequest;
import com.onedata.portal.dto.SqlAnalyzeResponse;
import com.onedata.portal.dto.StopQueryRequest;
import com.onedata.portal.dto.SqlQueryRequest;
import com.onedata.portal.dto.SqlQueryResponse;
import com.onedata.portal.entity.DataQueryHistory;
import com.onedata.portal.service.DataQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * 数据查询 Controller
 */
@RestController
@RequestMapping("/v1/data-query")
@RequiredArgsConstructor
public class DataQueryController {

    private final DataQueryService dataQueryService;

    @RequireAuth
    @PostMapping("/analyze")
    public Result<SqlAnalyzeResponse> analyze(@Validated @RequestBody SqlAnalyzeRequest request) {
        return Result.success(dataQueryService.analyzeQuery(request));
    }

    @RequireAuth
    @PostMapping("/execute")
    public Result<SqlQueryResponse> execute(@Validated @RequestBody SqlQueryRequest request) {
        // 用户上下文已由切面自动设置，DorisConnectionService会自动使用用户凭据
        return Result.success(dataQueryService.executeQuery(request));
    }

    @RequireAuth
    @PostMapping("/stop")
    public Result<Boolean> stop(@Validated @RequestBody StopQueryRequest request) {
        String userId = UserContextHolder.getCurrentUserId();
        return Result.success(dataQueryService.stopQuery(userId, request.getClientQueryId()));
    }

    @RequireAuth
    @GetMapping("/history")
    public Result<Page<DataQueryHistory>> history(@RequestParam(value = "pageNum", required = false) Integer pageNum,
                                                  @RequestParam(value = "pageSize", required = false) Integer pageSize,
                                                  @RequestParam(value = "clusterId", required = false) Long clusterId,
                                                  @RequestParam(value = "database", required = false) String database) {
        // 获取当前用户ID，只返回该用户的查询历史
        String userId = UserContextHolder.getCurrentUserId();
        return Result.success(dataQueryService.listHistoryByUser(userId, pageNum, pageSize, clusterId, database));
    }
}
