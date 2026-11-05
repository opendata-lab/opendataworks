package com.onedata.portal.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.onedata.portal.dto.PageResult;
import com.onedata.portal.dto.Result;
import com.onedata.portal.dto.table.TableVersionCompareRequest;
import com.onedata.portal.dto.table.TableVersionCompareResponse;
import com.onedata.portal.entity.DataTableVersion;
import com.onedata.portal.service.TableMetadataVersionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 表元数据版本历史 Controller
 */
@RestController
@RequestMapping("/v1/tables")
@RequiredArgsConstructor
public class DataTableVersionController {

    private final TableMetadataVersionService tableMetadataVersionService;

    /**
     * 分页查询表的版本历史（不含快照大字段）
     */
    @GetMapping("/{id}/versions")
    public Result<PageResult<DataTableVersion>> listVersions(
            @PathVariable Long id,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize) {
        Page<DataTableVersion> page = tableMetadataVersionService.listVersions(id, pageNum, pageSize);
        return Result.success(PageResult.of(page.getTotal(), page.getRecords()));
    }

    /**
     * 获取单个版本详情（含快照 JSON）
     */
    @GetMapping("/{id}/versions/{versionId}")
    public Result<DataTableVersion> getVersion(@PathVariable Long id, @PathVariable Long versionId) {
        return Result.success(tableMetadataVersionService.getVersion(id, versionId));
    }

    /**
     * 对比两个版本
     */
    @PostMapping("/{id}/versions/compare")
    public Result<TableVersionCompareResponse> compareVersions(
            @PathVariable Long id,
            @RequestBody TableVersionCompareRequest request) {
        return Result.success(tableMetadataVersionService.compare(id, request));
    }
}
