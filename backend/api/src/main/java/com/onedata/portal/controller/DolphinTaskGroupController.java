package com.onedata.portal.controller;

import com.onedata.portal.dto.DolphinTaskGroupOption;
import com.onedata.portal.dto.Result;
import com.onedata.portal.service.DolphinSchedulerService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * DolphinScheduler 任务组相关接口
 */
@RestController
@RequestMapping("/v1/dolphin")
@RequiredArgsConstructor
public class DolphinTaskGroupController {

    private final DolphinSchedulerService dolphinSchedulerService;

    /**
     * 查询可用的 Dolphin 任务组
     */
    @GetMapping("/task-groups")
    public Result<List<DolphinTaskGroupOption>> listTaskGroups(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long dolphinConfigId) {
        return Result.success(dolphinSchedulerService.listTaskGroups(keyword, dolphinConfigId));
    }
}
