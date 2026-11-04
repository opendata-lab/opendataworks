package com.onedata.portal.controller;

import com.onedata.auth.annotation.RequireAuth;
import com.onedata.portal.dto.Result;
import com.onedata.portal.entity.MinioConfig;
import com.onedata.portal.service.MinioConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * MinIO 环境配置管理
 */
@RestController
@RequestMapping("/v1/settings/minio")
@RequiredArgsConstructor
public class MinioConfigController {

    private final MinioConfigService minioConfigService;

    @GetMapping
    public Result<List<MinioConfig>> list(@RequestParam(required = false) String status) {
        return Result.success(minioConfigService.listAll(status));
    }

    @GetMapping("/{id}")
    public Result<MinioConfig> getById(@PathVariable Long id) {
        return Result.success(minioConfigService.getById(id));
    }

    @RequireAuth
    @PostMapping
    public Result<MinioConfig> create(@RequestBody MinioConfig config) {
        return Result.success(minioConfigService.create(config));
    }

    @RequireAuth
    @PutMapping("/{id}")
    public Result<MinioConfig> update(@PathVariable Long id, @RequestBody MinioConfig config) {
        return Result.success(minioConfigService.update(id, config));
    }

    @RequireAuth
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        minioConfigService.delete(id);
        return Result.success();
    }

    @RequireAuth
    @PostMapping("/{id}/default")
    public Result<Void> setDefault(@PathVariable Long id) {
        minioConfigService.setDefault(id);
        return Result.success();
    }
}

