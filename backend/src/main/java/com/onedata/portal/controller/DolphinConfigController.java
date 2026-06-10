package com.onedata.portal.controller;

import com.onedata.auth.annotation.RequireAuth;
import com.onedata.portal.dto.Result;
import com.onedata.portal.entity.DolphinConfig;
import com.onedata.portal.service.DolphinConfigService;
import com.onedata.portal.service.DolphinSchedulerService;
import com.onedata.portal.service.dolphin.DolphinOpenApiClient;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/v1/settings/dolphin")
@RequiredArgsConstructor
public class DolphinConfigController {

    private final DolphinConfigService dolphinConfigService;
    private final DolphinOpenApiClient dolphinOpenApiClient;
    private final DolphinSchedulerService dolphinSchedulerService;

    @GetMapping
    public Result<DolphinConfig> getConfig() {
        return Result.success(dolphinConfigService.getConfig());
    }

    @RequireAuth
    @PutMapping
    public Result<DolphinConfig> updateConfig(@RequestBody DolphinConfig config) {
        DolphinConfig updated = dolphinConfigService.updateConfig(config);
        // Clear project code dependency cache in apiClient if needed
        dolphinSchedulerService.clearProjectCodeCache();
        return Result.success(updated);
    }

    @RequireAuth
    @PostMapping("/test")
    public Result<Boolean> testConnection(@RequestBody DolphinConfig config) {
        // Use the API client to test connection with the provided config
        // Pass the temporary config to the client method
        boolean success = dolphinOpenApiClient.testConnection(config);
        return Result.success(success);
    }

    @GetMapping("/configs")
    public Result<List<DolphinConfig>> listConfigs(@RequestParam(required = false) Boolean activeOnly) {
        return Result.success(dolphinConfigService.listAll(activeOnly));
    }

    @GetMapping("/configs/{id}")
    public Result<DolphinConfig> getById(@PathVariable Long id) {
        return Result.success(dolphinConfigService.getById(id));
    }

    @RequireAuth
    @PostMapping("/configs")
    public Result<DolphinConfig> create(@RequestBody DolphinConfig config) {
        DolphinConfig created = dolphinConfigService.create(config);
        dolphinSchedulerService.clearProjectCodeCache();
        return Result.success(created);
    }

    @RequireAuth
    @PutMapping("/configs/{id}")
    public Result<DolphinConfig> update(@PathVariable Long id, @RequestBody DolphinConfig config) {
        DolphinConfig updated = dolphinConfigService.update(id, config);
        dolphinSchedulerService.clearProjectCodeCache();
        return Result.success(updated);
    }

    @RequireAuth
    @DeleteMapping("/configs/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        dolphinConfigService.delete(id);
        dolphinSchedulerService.clearProjectCodeCache();
        return Result.success();
    }

    @RequireAuth
    @PostMapping("/configs/{id}/default")
    public Result<Void> setDefault(@PathVariable Long id) {
        dolphinConfigService.setDefault(id);
        dolphinSchedulerService.clearProjectCodeCache();
        return Result.success();
    }

    @RequireAuth
    @PostMapping("/configs/{id}/test")
    public Result<Boolean> testSavedConnection(@PathVariable Long id) {
        return Result.success(dolphinSchedulerService.testConnection(id));
    }
}
