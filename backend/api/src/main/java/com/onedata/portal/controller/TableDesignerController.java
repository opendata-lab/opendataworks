package com.onedata.portal.controller;

import com.onedata.portal.dto.Result;
import com.onedata.portal.dto.TableCreateRequest;
import com.onedata.portal.dto.TableDesignPreviewResponse;
import com.onedata.portal.dto.TableNameGenerateRequest;
import com.onedata.portal.entity.DataTable;
import com.onedata.portal.service.TableCreateService;
import com.onedata.portal.service.TableNameGeneratorService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 表设计与创建 Controller
 */
@RestController
@RequestMapping("/v1/table-designer")
@RequiredArgsConstructor
public class TableDesignerController {

    private final TableNameGeneratorService tableNameGeneratorService;
    private final TableCreateService tableCreateService;

    /**
     * 生成规范化表名
     */
    @PostMapping("/table-name")
    public Result<String> generateTableName(@RequestBody TableNameGenerateRequest request) {
        return Result.success(tableNameGeneratorService.generate(request));
    }

    /**
     * 预览表设计（表名与 DDL）
     */
    @PostMapping("/preview")
    public Result<TableDesignPreviewResponse> preview(@RequestBody TableCreateRequest request) {
        return Result.success(tableCreateService.preview(request));
    }

    /**
     * 创建表并同步至 Doris
     */
    @PostMapping
    public Result<DataTable> create(@RequestBody TableCreateRequest request) {
        return Result.success(tableCreateService.create(request));
    }
}
